package com.rag.pipeline.common.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.rag.pipeline.common.agent.dto.AgentRequest;
import com.rag.pipeline.common.agent.dto.AgentResponse;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 프론트엔드 AgentPanel ↔ LLM 프록시.
 *
 * Azure AI Foundry (align-it-resource) 엔드포인트로 요청을 전달합니다.
 *
 * POST /api/agent/chat         — 단일 응답
 * POST /api/agent/chat/stream  — SSE 스트리밍
 * POST /api/agent/test         — API 연결 유효성 검증
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final ObjectMapper objectMapper;

    @Qualifier("pipelineExecutor")
    private final Executor pipelineExecutor;

    @Value("${spring.ai.openai.api-key:}")
    private String foundryApiKey;

    @Value("${app.ai.foundry.responses-url}")
    private String foundryUrl;

    @Value("${app.agent.timeout.stream:90}")
    private int streamTimeoutSec;

    @Value("${app.agent.timeout.sync:60}")
    private int syncTimeoutSec;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    // ── SSE 스트리밍 ──────────────────────────────────────────────

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
        @RequestHeader("X-LLM-Provider") String provider,
        @RequestHeader("X-LLM-Model")    String model,
        @RequestBody AgentRequest request
    ) {
        SseEmitter emitter = new SseEmitter(120_000L);

        CompletableFuture.runAsync(() -> {
            try {
                streamOpenAi(emitter, foundryApiKey, model, request);
            } catch (Exception e) {
                log.error("스트리밍 오류: {}", e.getMessage());
                try { emitter.completeWithError(e); } catch (Exception ignored) {}
            }
        }, pipelineExecutor);

        return emitter;
    }

    // ── 단일 응답 ─────────────────────────────────────────────────

    @PostMapping("/chat")
    public AgentResponse chat(
        @RequestHeader("X-LLM-Provider") String provider,
        @RequestHeader("X-LLM-Model")    String model,
        @RequestBody AgentRequest request
    ) throws Exception {
        return callOpenAi(foundryApiKey, model, request);
    }

    // ── API 연결 유효성 검증 ──────────────────────────────────────

    @PostMapping("/test")
    public Map<String, Boolean> test(
        @RequestHeader("X-LLM-Provider") String provider,
        @RequestHeader("X-LLM-Model")    String model,
        @RequestBody Map<String, String> body
    ) throws Exception {
        AgentRequest ping = new AgentRequest(
            List.of(Map.of("role", "user", "content", "Hello")),
            "Reply with 'OK' only.", null
        );
        callOpenAi(foundryApiKey, model, ping);
        return Map.of("ok", true);
    }

    // ── OpenAI / Azure AI Foundry 구현 ───────────────────────────

    private HttpRequest buildOpenAiRequest(String apiKey, String model,
                                            AgentRequest req, boolean stream) throws Exception {
        return HttpRequest.newBuilder()
            .uri(URI.create(foundryUrl))
            .header("Authorization", "Bearer " + apiKey)
            .header("content-type", "application/json")
            .timeout(Duration.ofSeconds(stream ? streamTimeoutSec : syncTimeoutSec))
            .POST(HttpRequest.BodyPublishers.ofString(buildOpenAiBody(model, req, stream)))
            .build();
    }

    private void streamOpenAi(SseEmitter emitter, String apiKey, String model, AgentRequest req) throws Exception {
        HttpRequest httpReq = buildOpenAiRequest(apiKey, model, req, true);
        HttpResponse<java.io.InputStream> resp =
            httpClient.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                if (data.isBlank()) continue;
                try {
                    JsonNode node = objectMapper.readTree(data);
                    String type = node.path("type").asText();
                    if ("response.output_text.delta".equals(type)) {
                        String text = node.path("delta").asText("");
                        if (!text.isEmpty()) {
                            emitter.send(SseEmitter.event()
                                .data("{\"delta\":" + objectMapper.writeValueAsString(text) + "}"));
                        }
                    } else if ("response.completed".equals(type) || "response.failed".equals(type)) {
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }
        emitter.send(SseEmitter.event().data("[DONE]"));
        emitter.complete();
    }

    private AgentResponse callOpenAi(String apiKey, String model, AgentRequest req) throws Exception {
        HttpRequest httpReq = buildOpenAiRequest(apiKey, model, req, false);
        HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
        JsonNode node = objectMapper.readTree(resp.body());
        StringBuilder content = new StringBuilder();
        for (JsonNode item : node.path("output")) {
            if ("message".equals(item.path("type").asText())) {
                for (JsonNode block : item.path("content")) {
                    if ("output_text".equals(block.path("type").asText())) {
                        content.append(block.path("text").asText());
                    }
                }
            }
        }
        return new AgentResponse(content.toString(), Map.of());
    }

    private String buildOpenAiBody(String model, AgentRequest req, boolean stream) throws Exception {
        List<Map<String, String>> input = new ArrayList<>();
        if (req.messages() != null) {
            req.messages().stream()
                .filter(m -> !"system".equals(m.get("role")))
                .forEach(input::add);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("stream", stream);
        payload.put("input", input);
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            payload.put("instructions", req.systemPrompt());
        }
        return objectMapper.writeValueAsString(payload);
    }
}
