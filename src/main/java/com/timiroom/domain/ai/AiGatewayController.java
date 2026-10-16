package com.timiroom.domain.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.spec.service.DocumentAccessService;
import com.timiroom.domain.usage.enums.UsageFeature;
import com.timiroom.domain.usage.service.AiCallService;
import com.timiroom.domain.usage.service.AiCallService.AiCall;
import com.timiroom.domain.usage.service.AiUsageRow;
import com.timiroom.infra.ragpipeline.RagPipelineClient;
import com.timiroom.infra.ragpipeline.RagPipelineClient.RawResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 브라우저가 AI 서버를 직접 부르던 기능을 백엔드가 대신 전달한다.
 * 모든 요청은 로그인·프로젝트 권한 → 사용량 한도 → AI 호출 → 사용량 기록 순서로 처리한다.
 * AI 서버 응답 본문과 상태는 바꾸지 않고 그대로 돌려준다(프론트 호환).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiGatewayController {

    private static final Pattern DOC_TYPE = Pattern.compile("[A-Za-z_-]{1,40}");
    private static final Set<String> RECOMMENDATIONS = Set.of("tech-stack", "persona", "features");
    private static final Duration TIMEOUT = Duration.ofSeconds(180);

    private final RagPipelineClient ragPipelineClient;
    private final AiCallService aiCallService;
    private final DocumentAccessService documentAccessService;
    private final ObjectMapper objectMapper;

    @PostMapping("/chat")
    public ResponseEntity<String> chat(HttpSession session, @RequestParam(required = false) Long projectId,
                                       @RequestBody String body) {
        return forward(session, projectId, UsageFeature.AI_CHAT, "/api/agent/chat", body);
    }

    @PostMapping("/test")
    public ResponseEntity<String> test(HttpSession session) {
        return forward(session, null, UsageFeature.AI_CHAT, "/api/agent/test", "{}");
    }

    @PostMapping("/documents/{docType}/edit")
    public ResponseEntity<String> editDocument(HttpSession session, @PathVariable String docType,
                                               @RequestParam(required = false) Long projectId,
                                               @RequestBody String body) {
        if (!DOC_TYPE.matcher(docType).matches()) return error(HttpStatus.BAD_REQUEST, "문서 종류가 올바르지 않습니다");
        return forward(session, projectId, UsageFeature.DOCUMENT_EDIT, "/api/v1/document/" + docType + "/edit", body);
    }

    @PostMapping("/recommendations/{kind}")
    public ResponseEntity<String> recommend(HttpSession session, @PathVariable String kind,
                                            @RequestParam(required = false) Long projectId,
                                            @RequestBody String body) {
        if (!RECOMMENDATIONS.contains(kind)) return error(HttpStatus.NOT_FOUND, "지원하지 않는 추천입니다");
        return forward(session, projectId, UsageFeature.RECOMMENDATION, "/api/v1/recommendation/" + kind, body);
    }

    /** 문서 화면 AI 채팅 스트리밍. AI 서버가 마지막에 보내는 사용량 이벤트는 기록하고 브라우저로는 넘기지 않는다. */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object chatStream(HttpSession session, @RequestParam(required = false) Long projectId,
                             @RequestBody String body) {
        Long memberId = memberId(session);
        if (memberId == null) return error(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다");
        AiCall call = begin(memberId, projectId, UsageFeature.AI_CHAT);

        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        List<AiUsageRow> usage = new ArrayList<>();
        Disposable subscription = ragPipelineClient.forwardStream("/api/agent/chat/stream", body, call.headers())
                .subscribe(data -> {
                    JsonNode usageNode = usageNode(data);
                    if (usageNode != null) {
                        usage.addAll(aiCallService.parseNode(usageNode));
                        return;
                    }
                    send(emitter, data);
                }, error -> {
                    log.warn("AI 채팅 스트리밍 실패: {}", error.getMessage());
                    send(emitter, "{\"error\":\"AI 응답을 받지 못했습니다. 잠시 후 다시 시도해 주세요.\"}");
                    send(emitter, "[DONE]");
                    aiCallService.finish(call, usage, "chat-stream");
                    emitter.complete();
                }, () -> {
                    aiCallService.finish(call, usage, "chat-stream");
                    emitter.complete();
                });
        emitter.onTimeout(subscription::dispose);
        emitter.onError(e -> subscription.dispose());
        return emitter;
    }

    private ResponseEntity<String> forward(HttpSession session, Long projectId, UsageFeature feature,
                                           String path, String body) {
        Long memberId = memberId(session);
        if (memberId == null) return error(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다");
        AiCall call = begin(memberId, projectId, feature);
        RawResponse response = ragPipelineClient.forward(path, body, call.headers(), TIMEOUT);
        aiCallService.finish(call, aiCallService.parseHeader(response.usageHeader()), path,
                response.status() < 400);
        return ResponseEntity.status(response.status())
                .contentType(MediaType.parseMediaType(response.contentType()))
                .body(response.body());
    }

    private AiCall begin(Long memberId, Long projectId, UsageFeature feature) {
        if (projectId != null) documentAccessService.requireRead(projectId, memberId);
        return aiCallService.begin(memberId, projectId, feature);
    }

    private JsonNode usageNode(String data) {
        if (data == null || !data.startsWith("{\"usage\"")) return null;
        try {
            JsonNode node = objectMapper.readTree(data);
            return node.size() == 1 && node.has("usage") ? node.get("usage") : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void send(SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (Exception e) {
            log.debug("브라우저 연결이 끊겨 스트림 전송을 멈춥니다");
        }
    }

    private static Long memberId(HttpSession session) {
        return (Long) session.getAttribute("memberId");
    }

    private static ResponseEntity<String> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"" + message + "\",\"detail\":\"" + message + "\"}");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "detail", e.getMessage()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> forbidden(SecurityException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage(), "detail", e.getMessage()));
    }
}
