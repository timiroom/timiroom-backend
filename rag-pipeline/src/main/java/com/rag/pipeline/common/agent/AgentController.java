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
 * 서버에 설정된 API 키(application.yml)를 사용하여
 * Anthropic / OpenAI 에 요청을 전달합니다.
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

    @Value("${spring.ai.anthropic.api-key:}")
    private String anthropicApiKey;

    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;

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
        String apiKey = "anthropic".equalsIgnoreCase(provider) ? anthropicApiKey : openAiApiKey;
        SseEmitter emitter = new SseEmitter(120_000L);

        CompletableFuture.runAsync(() -> {
            try {
                if ("anthropic".equalsIgnoreCase(provider)) {
                    streamAnthropic(emitter, apiKey, model, request);
                } else {
                    streamOpenAi(emitter, apiKey, model, request);
                }
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
        String apiKey = "anthropic".equalsIgnoreCase(provider) ? anthropicApiKey : openAiApiKey;
        return "anthropic".equalsIgnoreCase(provider)
            ? callAnthropic(apiKey, model, request)
            : callOpenAi(apiKey, model, request);
    }

    // ── API 연결 유효성 검증 ──────────────────────────────────────

    @PostMapping("/test")
    public Map<String, Boolean> test(
        @RequestHeader("X-LLM-Provider") String provider,
        @RequestHeader("X-LLM-Model")    String model,
        @RequestBody Map<String, String> body
    ) throws Exception {
        String apiKey = "anthropic".equalsIgnoreCase(provider) ? anthropicApiKey : openAiApiKey;
        AgentRequest ping = new AgentRequest(
            List.of(Map.of("role", "user", "content", "Hello")),
            "Reply with 'OK' only.", null
        );
        if ("anthropic".equalsIgnoreCase(provider)) callAnthropic(apiKey, model, ping);
        else callOpenAi(apiKey, model, ping);
        return Map.of("ok", true);
    }

    // ── Anthropic 구현 ────────────────────────────────────────────

    private HttpRequest buildAnthropicRequest(String apiKey, String model,
                                               AgentRequest req, boolean stream) throws Exception {
        return HttpRequest.newBuilder()
            .uri(URI.create("https://api.anthropic.com/v1/messages"))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .timeout(Duration.ofSeconds(stream ? streamTimeoutSec : syncTimeoutSec))
            .POST(HttpRequest.BodyPublishers.ofString(buildAnthropicBody(model, req, stream)))
            .build();
    }

    private void streamAnthropic(SseEmitter emitter, String apiKey, String model, AgentRequest req) throws Exception {
        HttpRequest httpReq = buildAnthropicRequest(apiKey, model, req, true);
        HttpResponse<java.io.InputStream> resp =
            httpClient.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isBlank()) continue;
                try {
                    JsonNode node = objectMapper.readTree(data);
                    if ("content_block_delta".equals(node.path("type").asText())) {
                        String text = node.path("delta").path("text").asText("");
                        if (!text.isEmpty()) {
                            emitter.send(SseEmitter.event()
                                .data("{\"delta\":" + objectMapper.writeValueAsString(text) + "}"));
                        }
                    } else if ("message_stop".equals(node.path("type").asText())) {
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }
        emitter.send(SseEmitter.event().data("[DONE]"));
        emitter.complete();
    }

    private AgentResponse callAnthropic(String apiKey, String model, AgentRequest req) throws Exception {
        HttpRequest httpReq = buildAnthropicRequest(apiKey, model, req, false);
        HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
        JsonNode node = objectMapper.readTree(resp.body());
        String content = node.path("content").get(0).path("text").asText();
        return new AgentResponse(content, Map.of());
    }

    private String buildAnthropicBody(String model, AgentRequest req, boolean stream) throws Exception {
        List<Map<String, String>> rawMessages = req.messages() == null ? List.of() : req.messages();
        List<Map<String, String>> messages = rawMessages.stream()
            .filter(m -> !"system".equals(m.get("role")))
            .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("max_tokens", 4096);
        payload.put("stream", stream);
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            payload.put("system", req.systemPrompt());
        }
        payload.put("messages", messages);
        return objectMapper.writeValueAsString(payload);
    }

    // ── OpenAI 구현 ───────────────────────────────────────────────

    private HttpRequest buildOpenAiRequest(String apiKey, String model,
                                            AgentRequest req, boolean stream) throws Exception {
        return HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
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
                    String text = node.path("choices").get(0)
                        .path("delta").path("content").asText("");
                    if (!text.isEmpty()) {
                        emitter.send(SseEmitter.event()
                            .data("{\"delta\":" + objectMapper.writeValueAsString(text) + "}"));
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
        String content = node.path("choices").get(0).path("message").path("content").asText();
        return new AgentResponse(content, Map.of());
    }

    private String buildOpenAiBody(String model, AgentRequest req, boolean stream) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", req.systemPrompt()));
        }
        if (req.messages() != null) messages.addAll(req.messages());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("stream", stream);
        payload.put("messages", messages);
        return objectMapper.writeValueAsString(payload);
    }
}
