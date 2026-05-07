package com.rag.pipeline.phase2.graph;

import com.rag.pipeline.phase2.agent.*;
import com.rag.pipeline.phase2.sse.PipelineProgressService;
import com.rag.pipeline.phase2.state.PipelineState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Phase 2 전체 워크플로우 그래프
 *
 * 흐름:
 *   Search 에이전트 → PM 에이전트
 *     → PRD 에이전트 + DBA·API 검증 (최대 3회 rollback)
 *     → QA 에이전트 (최대 5회 retry)
 *     → Phase 3 진입
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrchestrationGraph {

    private final SearchAgent              searchAgent;
    private final PmAgent                  pmAgent;
    private final DbaAgent                 dbaAgent;
    private final ApiAgent                 apiAgent;
    private final QaAgent                  qaAgent;
    private final PrdAgent                 prdAgent;
    private final PipelineProgressService  progressService;

    private static final int MAX_QA_RETRY = 5;

    // ── 외부 진입점 ──────────────────────────────────────────────────

    /** 폼 기반 실행 — pipelineId로 SSE 이벤트 발행 */
    public PipelineState run(PipelineState initialState, String pipelineId) {
        log.info("=== Phase 2 오케스트레이션 시작 ===");

        progressService.send(pipelineId, "SEARCH", "시장 조사 중...", 30);
        PipelineState afterSearch = searchAgent.execute(initialState);

        progressService.send(pipelineId, "PM", "기능 분석 및 설계 지시 생성 중...", 40);
        PipelineState afterPm = pmAgent.execute(afterSearch);

        progressService.send(pipelineId, "PRD", "PRD 문서 작성 중...", 50);
        PipelineState afterPrdDbaApi = runPrdWithRollback(afterPm, pipelineId);

        progressService.send(pipelineId, "QA", "QA 검수 중...", 75);
        PipelineState finalState = runQaRetryOnly(afterPrdDbaApi, 0, pipelineId);

        log.info("=== Phase 2 완료 ===");
        return finalState;
    }

    /** 레거시 — SSE 없이 실행 */
    public PipelineState run(PipelineState initialState) {
        return run(initialState, null);
    }

    /** 레거시 — 쿼리 문자열로 직접 실행 */
    public PipelineState execute(String userQuery, String contextPrompt) {
        log.info("=== Phase 2 오케스트레이션 시작 ===");
        PipelineState initialState = PipelineState.builder()
                .userQuery(userQuery)
                .contextPrompt(contextPrompt)
                .build();
        return run(initialState, null);
    }

    // ── PRD ↔ DBA/API 롤백 루프 ──────────────────────────────────────

    private PipelineState runPrdWithRollback(PipelineState pmState, String pipelineId) {
        PipelineState current = pmState;

        for (int attempt = 0; attempt <= 2; attempt++) {
            log.info("PRD 에이전트 실행 중... (시도 {})", attempt + 1);
            PipelineState afterPrd = prdAgent.execute(current);

            progressService.send(pipelineId, "DBA_API",
                "DB 스키마 · API 설계 중...", 60);

            CompletableFuture<PipelineState> dbaFuture =
                    CompletableFuture.supplyAsync(() -> dbaAgent.execute(afterPrd));
            CompletableFuture<PipelineState> apiFuture =
                    CompletableFuture.supplyAsync(() -> apiAgent.execute(afterPrd));

            PipelineState dbaResult = dbaFuture.join();
            PipelineState apiResult = apiFuture.join();

            boolean dbaFeedback = dbaResult.getPrdFeedbackFromDba() != null
                    && !dbaResult.getPrdFeedbackFromDba().isBlank();
            boolean apiFeedback = apiResult.getPrdFeedbackFromApi() != null
                    && !apiResult.getPrdFeedbackFromApi().isBlank();

            if (!dbaFeedback && !apiFeedback) {
                log.info("PRD ↔ DBA/API 검증 통과 (시도 {})", attempt + 1);
                return afterPrd.toBuilder()
                        .dbSchema(dbaResult.getDbSchema())
                        .apiSpec(apiResult.getApiSpec())
                        .build();
            }

            if (attempt < 2) {
                log.warn("PRD rollback #{}", attempt + 1);
                progressService.send(pipelineId, "PRD_ROLLBACK",
                    String.format("PRD 재작성 중... (%d/2회)", attempt + 1), 52);
                current = afterPrd.toBuilder()
                        .prdFeedbackFromDba(dbaResult.getPrdFeedbackFromDba())
                        .prdFeedbackFromApi(apiResult.getPrdFeedbackFromApi())
                        .rollbackCount(attempt + 1)
                        .build();
            } else {
                log.warn("PRD rollback 최대 횟수 초과 — 현재 결과로 진행");
                return afterPrd.toBuilder()
                        .dbSchema(dbaResult.getDbSchema())
                        .apiSpec(apiResult.getApiSpec())
                        .build();
            }
        }

        return current;
    }

    // ── QA 재시도 루프 ────────────────────────────────────────────────

    private PipelineState runQaRetryOnly(PipelineState state, int attempt, String pipelineId) {

        if (attempt > 0) {
            progressService.send(pipelineId, "QA_RETRY",
                String.format("QA 재검수 중... (%d/%d회)", attempt, MAX_QA_RETRY), 76);
        }

        log.info("QA 에이전트 실행 중... (시도 {})", attempt + 1);
        PipelineState qaResult = qaAgent.execute(state);

        boolean qaFailed = qaResult.getLastValidationError() != null
                && !qaResult.getLastValidationError().isBlank();

        if (!qaFailed) {
            log.info("QA 검수 통과 (시도 {})", attempt + 1);
            return qaResult;
        }

        if (attempt >= MAX_QA_RETRY) {
            log.warn("QA 최대 재시도 초과 ({}) — Phase 3로 위임", MAX_QA_RETRY);
            return qaResult.toBuilder()
                    .lastValidationError("")
                    .statusMessage("QA 최대 재시도 초과 — Phase 3 형식 검증으로 위임")
                    .build();
        }

        log.warn("QA 검수 실패 — DBA/API 재실행 후 재시도 ({}/{})", attempt + 1, MAX_QA_RETRY);

        String retryContext = state.getContextPrompt()
                + "\n\n=== 이전 설계의 치명적 결함 (반드시 수정) ===\n"
                + qaResult.getLastValidationError()
                + "\n\n위 결함을 모두 수정하여 완전한 설계를 다시 생성하세요.";

        PipelineState retryBase = state.toBuilder()
                .contextPrompt(retryContext)
                .lastValidationError("")
                .retryCount(attempt + 1)
                .build();

        CompletableFuture<PipelineState> dbaFuture =
                CompletableFuture.supplyAsync(() -> dbaAgent.execute(retryBase));
        CompletableFuture<PipelineState> apiFuture =
                CompletableFuture.supplyAsync(() -> apiAgent.execute(retryBase));

        PipelineState dbaResult = dbaFuture.join();
        PipelineState apiResult = apiFuture.join();

        PipelineState merged = retryBase.toBuilder()
                .dbSchema(dbaResult.getDbSchema())
                .apiSpec(apiResult.getApiSpec())
                .prdFeedbackFromDba(dbaResult.getPrdFeedbackFromDba())
                .prdFeedbackFromApi(apiResult.getPrdFeedbackFromApi())
                .retryCount(attempt + 1)
                .build();

        return runQaRetryOnly(merged, attempt + 1, pipelineId);
    }
}
