package com.rag.pipeline.common.tracing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LangSmith 트레이싱 클라이언트
 *
 * 모든 LLM 호출 결과를 LangSmith에 비동기로 기록한다.
 * 트레이싱 실패는 파이프라인에 영향을 주지 않는다.
 *
 * LangSmith 설정 (application.yml):
 *   langsmith.enabled: true
 *   langsmith.api-key: ls__xxxx
 *   langsmith.project: timiroom
 */
@Slf4j
@Service
public class LangSmithClient {

    @Value("${langsmith.enabled:false}")
    private boolean enabled;

    @Value("${langsmith.api-key:}")
    private String apiKey;

    @Value("${langsmith.project:timiroom}")
    private String project;

    private static final String LANGSMITH_URL = "https://api.smith.langchain.com/runs";

    // LangSmith용 별도 RestClient — 인터셉터 없는 순수 클라이언트
    private final RestClient restClient = RestClient.create();

    /**
     * LLM 호출 결과를 LangSmith에 비동기 기록
     *
     * @param runName      실행 이름 (예: "PmAgent.executeDraft")
     * @param model        모델명 (예: "gpt-5.4-mini")
     * @param input        요청 내용 (JSON 문자열)
     * @param output       응답 텍스트
     * @param durationMs   응답 시간 (ms)
     * @param inputTokens  입력 토큰 수
     * @param outputTokens 출력 토큰 수
     */
    @Async
    public void traceAsync(String runName, String agentTag, String model, String input, String output,
                           long durationMs, int inputTokens, int outputTokens) {
        if (!enabled || apiKey == null || apiKey.isBlank()) return;

        try {
            Instant end   = Instant.now();
            Instant start = end.minusMillis(durationMs);

            Map<String, Object> run = Map.of(
                "id",           UUID.randomUUID().toString(),
                "name",         runName,
                "run_type",     "llm",
                "start_time",   start.toString(),
                "end_time",     end.toString(),
                "inputs",       Map.of(
                    "model",    model,
                    "input",    input
                ),
                "outputs",      Map.of("output", output),
                "extra",        Map.of(
                    "metadata", Map.of(
                        "model",         model,
                        "agent",         agentTag,
                        "input_tokens",  inputTokens,
                        "output_tokens", outputTokens,
                        "total_tokens",  inputTokens + outputTokens,
                        "duration_ms",   durationMs
                    )
                ),
                "project_name", project,
                "tags",         List.of("timiroom", model, agentTag)
            );

            restClient.post()
                .uri(LANGSMITH_URL)
                .header("x-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(run)
                .retrieve()
                .toBodilessEntity();

            log.debug("[LangSmith] 트레이스 전송 완료 — {} [{}] ({}ms, in:{} out:{})",
                runName, agentTag, durationMs, inputTokens, outputTokens);

        } catch (Exception e) {
            log.debug("[LangSmith] 트레이스 실패 (파이프라인 영향 없음): {}", e.getMessage());
        }
    }
}
