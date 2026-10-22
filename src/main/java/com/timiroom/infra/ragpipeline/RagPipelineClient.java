package com.timiroom.infra.ragpipeline;

import com.timiroom.domain.usage.service.AiUsageScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class RagPipelineClient {

    private final WebClient webClient;
    /** 기획 대화 한 턴을 기다리는 시간. 운영 API 프록시(nginx)가 120초에서 끊으므로 그보다 짧게 둔다. */
    private final Duration chatTimeout;

    public RagPipelineClient(
            @Value("${rag-pipeline.base-url}") String baseUrl,
            @Value("${rag-pipeline.internal-token:}") String internalToken,
            @Value("${rag-pipeline.chat-timeout-seconds:100}") long chatTimeoutSeconds,
            ObjectMapper objectMapper) {
        this.chatTimeout = Duration.ofSeconds(chatTimeoutSeconds);
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(config -> config.defaultCodecs().maxInMemorySize(50 * 1024 * 1024));
        // AI 서버는 이 토큰이 있어야 AI 경로를 열어 준다(브라우저 직접 호출 차단).
        if (internalToken != null && !internalToken.isBlank()) {
            builder.defaultHeader("X-Timiroom-Internal-Token", internalToken.trim());
        }
        this.webClient = builder.build();
        log.info("RagPipelineClient initialized with base-url: {}", baseUrl);
    }

    /** AI 서버 응답 그대로(상태·본문·사용량 헤더). 프록시 경로에서 쓴다. */
    public record RawResponse(int status, String contentType, String body, String usageHeader) {}

    /** 채팅 응답의 data와 사용량 헤더 */
    public record ChatReply(Map<String, Object> data, String usageHeader) {}

    static boolean causedByTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause())
            if (current instanceof java.util.concurrent.TimeoutException) return true;
        return false;
    }

    private RuntimeException unavailable(String op, Throwable cause) {
        log.error("RAG 파이프라인 연결 실패 [{}]: {}", op, cause.getMessage());
        return new IllegalStateException("RAG 파이프라인 서비스에 연결할 수 없습니다. 서버가 실행 중인지 확인해주세요.", cause);
    }

    /**
     * 파이프라인 시작 요청 → pipelineId(UUID) 반환
     * POST /api/v1/orchestration/generate?mode={mode} (multipart/form-data)
     *
     * @param mode "collaborative" 또는 "sequential" — rag-pipeline OrchestrationController로 그대로 전달
     */
    public String generate(String requestJson, List<MultipartFile> files, String mode) {
        return generate(requestJson, files, mode, Map.of());
    }

    public String generate(String requestJson, List<MultipartFile> files, String mode, Map<String, String> headers) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("request", requestJson).contentType(MediaType.APPLICATION_JSON);

        if (files != null) {
            for (MultipartFile file : files) {
                try {
                    byte[] bytes = file.getBytes();
                    builder.part("files", new ByteArrayResource(bytes) {
                        @Override
                        public String getFilename() { return file.getOriginalFilename(); }
                    }).contentType(MediaType.APPLICATION_OCTET_STREAM);
                } catch (Exception e) {
                    throw new RuntimeException("파일 처리 실패: " + file.getOriginalFilename(), e);
                }
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> response;
        try {
            response = webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/orchestration/generate")
                            .queryParam("mode", mode)
                            .build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .headers(h -> headers.forEach(h::set))
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (WebClientRequestException e) {
            throw unavailable("generate", e);
        }

        if (response == null) throw new RuntimeException("rag-pipeline 응답이 없습니다");

        // ApiResponse { success, code, data: { pipelineId: "uuid" } }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        return (String) data.get("pipelineId");
    }

    /**
     * 프로젝트 인테이크 채팅 메시지 전송
     * POST /api/v1/chat/message
     */
    public Map<String, Object> chat(Object requestBody) {
        return chat(requestBody, Map.of()).data();
    }

    public ChatReply chat(Object requestBody, Map<String, String> headers) {
        ResponseEntity<Map<String, Object>> response;
        try {
            response = webClient.post()
                    .uri("/api/v1/chat/message")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> headers.forEach(h::set))
                    .bodyValue(requestBody)
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(chatTimeout)
                    .block();
        } catch (WebClientRequestException e) {
            throw unavailable("chat", e);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.warn("RAG 파이프라인 채팅 오류 status={}", e.getStatusCode().value());
            throw AiServiceException.from(e, "AI 응답을 받지 못했습니다. 잠시 후 다시 보내 주세요.");
        } catch (RuntimeException e) {
            if (causedByTimeout(e)) {
                log.warn("RAG 파이프라인 채팅 응답 시간 초과 ({}초)", chatTimeout.toSeconds());
                throw new IllegalStateException("AI 응답이 오래 걸려 중단했습니다. 잠시 후 다시 보내 주세요.", e);
            }
            throw e;
        }

        if (response == null || response.getBody() == null) throw new RuntimeException("rag-pipeline 채팅 응답이 없습니다");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        return new ChatReply(data, response.getHeaders().getFirst(USAGE_HEADER));
    }

    private static final String USAGE_HEADER = "X-Timiroom-Usage";

    /** JSON 요청을 그대로 넘기고 상태·본문을 그대로 받는다. 4xx·5xx도 예외 없이 돌려준다. */
    public RawResponse forward(String path, String jsonBody, Map<String, String> headers, Duration timeout) {
        try {
            RawResponse response = webClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> headers.forEach(h::set))
                    .bodyValue(jsonBody == null ? "{}" : jsonBody)
                    .exchangeToMono(r -> r.bodyToMono(String.class).defaultIfEmpty("")
                            .map(body -> new RawResponse(r.statusCode().value(),
                                    r.headers().contentType().map(MediaType::toString).orElse(MediaType.APPLICATION_JSON_VALUE),
                                    body, r.headers().asHttpHeaders().getFirst(USAGE_HEADER))))
                    .timeout(timeout)
                    .block();
            if (response == null) throw new IllegalStateException("AI 서버 응답이 없습니다");
            return response;
        } catch (WebClientRequestException e) {
            throw unavailable("forward " + path, e);
        }
    }

    /** 스트리밍 응답(SSE)의 data 값을 하나씩 흘려보낸다. */
    public Flux<String> forwardStream(String path, String jsonBody, Map<String, String> headers) {
        return webClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(h -> headers.forEach(h::set))
                .bodyValue(jsonBody == null ? "{}" : jsonBody)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .map(event -> event.data() == null ? "" : event.data())
                .onErrorMap(WebClientRequestException.class, e -> unavailable("stream " + path, e));
    }

    /** 전용 PR Consistency Agent에 구조화된 검증 요청을 전달한다. */
    public JsonNode reviewPullRequestConsistency(Object requestBody) {
        JsonNode response;
        try {
            var entity = webClient.post()
                    .uri("/api/v1/agents/pr-consistency/review")
                    .header(AiUsageScope.TRACKING_HEADER, AiUsageScope.active() ? "1" : "0")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .toEntity(JsonNode.class)
                    .timeout(Duration.ofSeconds(75))
                    .block();
            response = entity == null ? null : entity.getBody();
            if (entity != null) AiUsageScope.addHeader(entity.getHeaders().getFirst("X-Timiroom-Usage"));
        } catch (WebClientRequestException e) {
            throw unavailable("reviewPullRequestConsistency", e);
        }
        if (response == null || !response.path("findings").isArray()) {
            throw new IllegalStateException("PR Consistency Agent의 구조화된 응답이 없습니다");
        }
        return response;
    }

    /** 전용 QA Scenario Agent에 시나리오 생성을 요청한다. */
    public JsonNode generateQaScenarios(Object requestBody, java.util.Map<String, String> headers) {
        JsonNode response;
        try {
            var entity = webClient.post()
                    .uri("/api/v1/agents/qa-scenario/generate")
                    .header(AiUsageScope.TRACKING_HEADER, AiUsageScope.active() ? "1" : "0")
                    .headers(h -> { if (headers != null) headers.forEach(h::set); })
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .toEntity(JsonNode.class)
                    .timeout(Duration.ofSeconds(90))
                    .block();
            response = entity == null ? null : entity.getBody();
            if (entity != null) AiUsageScope.addHeader(entity.getHeaders().getFirst(USAGE_HEADER));
        } catch (WebClientRequestException e) {
            throw unavailable("generateQaScenarios", e);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.warn("QA 시나리오 에이전트 오류 | generateQaScenarios status={}", e.getStatusCode().value());
            throw AiServiceException.from(e, "AI가 QA 작업을 마치지 못했습니다. 잠시 후 다시 시도해 주세요.");
        } catch (RuntimeException e) {
            if (!(e.getCause() instanceof java.util.concurrent.TimeoutException)) throw e;
            throw new IllegalStateException("AI 응답이 늦어 QA 작업을 마치지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (response == null || !response.path("scenarios").isArray()) {
            throw new IllegalStateException("QA Scenario Agent의 구조화된 응답이 없습니다");
        }
        return response;
    }

    /** 전용 QA Scenario Agent에 시나리오 평가를 요청한다. */
    public JsonNode evaluateQaScenario(Object requestBody, java.util.Map<String, String> headers) {
        JsonNode response;
        try {
            var entity = webClient.post()
                    .uri("/api/v1/agents/qa-scenario/evaluate")
                    .header(AiUsageScope.TRACKING_HEADER, AiUsageScope.active() ? "1" : "0")
                    .headers(h -> { if (headers != null) headers.forEach(h::set); })
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .toEntity(JsonNode.class)
                    .timeout(Duration.ofSeconds(90))
                    .block();
            response = entity == null ? null : entity.getBody();
            if (entity != null) AiUsageScope.addHeader(entity.getHeaders().getFirst(USAGE_HEADER));
        } catch (WebClientRequestException e) {
            throw unavailable("evaluateQaScenario", e);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.warn("QA 시나리오 에이전트 오류 | evaluateQaScenario status={}", e.getStatusCode().value());
            throw AiServiceException.from(e, "AI가 QA 작업을 마치지 못했습니다. 잠시 후 다시 시도해 주세요.");
        } catch (RuntimeException e) {
            if (!(e.getCause() instanceof java.util.concurrent.TimeoutException)) throw e;
            throw new IllegalStateException("AI 응답이 늦어 QA 작업을 마치지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (response == null || response.path("verdict").isMissingNode()) {
            throw new IllegalStateException("QA Scenario Agent의 구조화된 응답이 없습니다");
        }
        return response;
    }

    /**
     * SSE 진행 스트림 구독
     * GET /api/v1/orchestration/progress/{pipelineId}
     * - event: "progress" | "complete" | "error"
     */
    public Flux<ServerSentEvent<String>> getProgressFlux(String pipelineId) {
        return webClient.get()
                .uri("/api/v1/orchestration/progress/{id}", pipelineId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .onErrorMap(WebClientRequestException.class, e -> unavailable("progress", e))
                .doOnError(e -> log.error("SSE 스트림 오류 | pipelineId: {}", pipelineId, e));
    }
}
