package com.rag.pipeline.common.tracing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Azure Foundry LLM 호출을 자동으로 LangSmith에 트레이싱하는 RestClient 인터셉터.
 *
 * - /openai/v1/responses 경로만 추적 (임베딩·기타 경로는 통과)
 * - 에이전트 코드 수정 없이 모든 호출 자동 추적
 * - 호출 스택에서 에이전트 이름 자동 추출 (예: "PmAgent.executeDraft")
 * - 응답 바디를 버퍼링해 caller가 정상적으로 읽을 수 있도록 보장
 * - 트레이싱 실패는 예외를 삼켜 파이프라인에 영향 없음
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangSmithTracingInterceptor implements ClientHttpRequestInterceptor {

    private final LangSmithClient langSmithClient;
    private final ObjectMapper    objectMapper;

    private static final String FOUNDRY_TRACE_PATH = "/openai/v1/responses";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        // Foundry LLM 호출이 아니면 그냥 통과
        if (!request.getURI().getPath().endsWith(FOUNDRY_TRACE_PATH)) {
            return execution.execute(request, body);
        }

        long start    = System.currentTimeMillis();
        ClientHttpResponse response = execution.execute(request, body);
        long durationMs = System.currentTimeMillis() - start;

        // 응답 바디 버퍼링 (caller도 읽을 수 있도록 ByteArray로 보관)
        byte[] responseBytes = response.getBody().readAllBytes();

        // 비동기 트레이싱 (실패해도 파이프라인 무관)
        try {
            JsonNode reqNode  = objectMapper.readTree(body);
            JsonNode respNode = objectMapper.readTree(responseBytes);

            String model        = reqNode.path("model").asText("unknown");
            String input        = reqNode.path("input").toString();
            String output       = extractOutputText(respNode);
            int    inputTokens  = respNode.path("usage").path("input_tokens").asInt(0);
            int    outputTokens = respNode.path("usage").path("output_tokens").asInt(0);
            String runName      = inferRunName();
            String agentTag     = extractAgentTag(runName);

            langSmithClient.traceAsync(runName, agentTag, model, input, output,
                durationMs, inputTokens, outputTokens);

        } catch (Exception e) {
            log.debug("[LangSmith] 인터셉터 파싱 실패 (무시): {}", e.getMessage());
        }

        // 버퍼링된 바디로 응답 래핑해 반환
        return new BufferedClientHttpResponse(response, responseBytes);
    }

    // 에이전트 내부 헬퍼 메서드는 스킵하고 실제 진입점(executeDraft/refine/execute 등)을 우선 탐지
    private static final java.util.Set<String> SKIP_METHODS = java.util.Set.of(
        "callChatCompletions", "callFoundryApi", "extractText", "inferRunName"
    );

    /** 호출 스택에서 에이전트/서비스 이름 자동 추출 */
    String inferRunName() {
        String fallback = null;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String cls = frame.getClassName();
            if (cls.startsWith("com.rag.pipeline") &&
                !cls.contains("LangSmith") &&
                !cls.contains("$")) {

                String simple = cls.substring(cls.lastIndexOf('.') + 1);
                if (fallback == null) fallback = simple + "." + frame.getMethodName();
                if (!SKIP_METHODS.contains(frame.getMethodName())) {
                    return simple + "." + frame.getMethodName();
                }
            }
        }
        return fallback != null ? fallback : "unknown";
    }

    /** runName에서 에이전트 클래스명만 추출 (예: "PmAgent.executeDraft" → "PmAgent") */
    String extractAgentTag(String runName) {
        int dot = runName.indexOf('.');
        return dot > 0 ? runName.substring(0, dot) : runName;
    }

    /** Azure Foundry 응답에서 텍스트 추출 */
    private String extractOutputText(JsonNode root) {
        for (JsonNode item : root.path("output")) {
            if ("message".equals(item.path("type").asText())) {
                for (JsonNode block : item.path("content")) {
                    if ("output_text".equals(block.path("type").asText())) {
                        return block.path("text").asText();
                    }
                }
            }
        }
        return "";
    }

    // ── 응답 바디 버퍼링 래퍼 ────────────────────────────────────────

    private static class BufferedClientHttpResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final byte[]             body;

        BufferedClientHttpResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body     = body;
        }

        @Override public InputStream    getBody()       { return new ByteArrayInputStream(body); }
        @Override public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        @Override public String         getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override public HttpHeaders    getHeaders()    { return delegate.getHeaders(); }
        @Override public void           close()         { delegate.close(); }
    }
}
