package com.rag.pipeline.phase2.graph;

import com.rag.pipeline.phase2.agent.*;
import com.rag.pipeline.phase2.rl.AgentRLService;
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
    private final AgentRLService           agentRLService;

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
        PipelineState afterQa = runQaRetryOnly(afterPrdDbaApi, 0, pipelineId);

        // RL: QA 결과를 보상으로 변환 → DBA/API temperature 업데이트
        // retryCount 패널티: 재시도할수록 원점수에서 0.15씩 차감
        double rawScore  = afterQa.getQaQualityScore();
        int    retries   = afterQa.getRetryCount();
        double finalScore = Math.max(0.05, rawScore - 0.15 * retries);
        String rlKey = afterQa.getSessionId() != null ? afterQa.getSessionId() : pipelineId;
        agentRLService.applyQaFeedback(rlKey, finalScore);
        log.info("Agent RL 피드백 적용 — rawScore:{}, retries:{}, finalScore:{}",
                String.format("%.2f", rawScore), retries, String.format("%.2f", finalScore));

        log.info("=== Phase 2 완료 ===");
        return afterQa;
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
                // DBA_API(60%) 이후이므로 62→64로 항상 순방향 증가
                int rollbackPct = 62 + attempt * 2;
                progressService.send(pipelineId, "PRD_ROLLBACK",
                    String.format("PRD 재작성 중... (%d/2회)", attempt + 1), rollbackPct);
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

    /**
     * QA 재시도 percent 계산
     *
     * QA 구간: 75% ~ 84% (PHASE3=85% 직전까지)
     * attempt 0: 재검수 75% (run()에서 이미 전송), 수정 76%
     * attempt 1: 재검수 77%, 수정 78%
     * attempt 2: 재검수 79%, 수정 80%
     * attempt 3: 재검수 81%, 수정 82%
     * attempt 4: 재검수 83%, 수정 84%
     * → 항상 오름차순, 역주행 없음
     */
    private int qaCheckPct(int attempt) { return Math.min(75 + attempt * 2, 84); }
    private int qaFixPct(int attempt)   { return Math.min(75 + attempt * 2 + 1, 84); }

    private PipelineState runQaRetryOnly(PipelineState state, int attempt, String pipelineId) {

        if (attempt > 0) {
            progressService.send(pipelineId, "QA_RETRY",
                String.format("QA 재검수 중... (%d/%d회)", attempt, MAX_QA_RETRY),
                qaCheckPct(attempt));
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

        java.util.List<String> dbIssues  = qaResult.getQaDbIssues();
        java.util.List<String> apiIssues = qaResult.getQaApiIssues();
        java.util.List<String> prdIssues = qaResult.getQaPrdIssues();
        int fixPct = qaFixPct(attempt);

        log.warn("QA 검수 실패 — DB:{}, API:{}, PRD:{} 결함 — 해당 에이전트만 선택적 재수정 ({}/{})",
                dbIssues.size(), apiIssues.size(), prdIssues.size(), attempt + 1, MAX_QA_RETRY);

        PipelineState retryBase = state.toBuilder()
                .lastValidationError("")
                .retryCount(attempt + 1)
                .build();

        // ── PRD 수정 (prdIssues가 있을 때만) ────────────────────────
        PipelineState afterPrd = retryBase;
        if (!prdIssues.isEmpty()) {
            log.info("PRD 선택적 수정 — {}개 결함", prdIssues.size());
            progressService.send(pipelineId, "QA_RETRY",
                String.format("PRD 결함 수정 중... (%d/%d회)", attempt + 1, MAX_QA_RETRY), fixPct);
            afterPrd = prdAgent.patchFromQa(retryBase, prdIssues);
        }

        // ── DBA / API 선택적 수정 ────────────────────────────────────
        boolean needDba = !dbIssues.isEmpty();
        boolean needApi = !apiIssues.isEmpty();

        if (!needDba && !needApi) {
            log.info("DB/API 결함 없음 — PRD 수정 결과로 QA 재검수");
            return runQaRetryOnly(afterPrd, attempt + 1, pipelineId);
        }

        String baseCtx = state.getContextPrompt() != null ? state.getContextPrompt() : "";
        String dbIssueSection  = needDba ? "\n\n=== QA 지적 결함 (DB 스키마) ===\n"
                + String.join("\n", dbIssues) : "";
        String apiIssueSection = needApi ? "\n\n=== QA 지적 결함 (API 스펙) ===\n"
                + String.join("\n", apiIssues) : "";

        PipelineState dbaResult = afterPrd;
        PipelineState apiResult = afterPrd;

        if (needDba && needApi) {
            progressService.send(pipelineId, "QA_RETRY",
                String.format("DB · API 결함 수정 중... (%d/%d회)", attempt + 1, MAX_QA_RETRY), fixPct);
            PipelineState dbaBase = afterPrd.toBuilder().contextPrompt(baseCtx + dbIssueSection).build();
            PipelineState apiBase = afterPrd.toBuilder().contextPrompt(baseCtx + apiIssueSection).build();

            CompletableFuture<PipelineState> dbaFuture = CompletableFuture.supplyAsync(() -> dbaAgent.refine(dbaBase));
            CompletableFuture<PipelineState> apiFuture = CompletableFuture.supplyAsync(() -> apiAgent.refine(apiBase));

            dbaResult = dbaFuture.join();
            apiResult = apiFuture.join();

        } else if (needDba) {
            progressService.send(pipelineId, "QA_RETRY",
                String.format("DB 스키마 결함 수정 중... (%d/%d회)", attempt + 1, MAX_QA_RETRY), fixPct);
            PipelineState dbaBase = afterPrd.toBuilder().contextPrompt(baseCtx + dbIssueSection).build();
            dbaResult = dbaAgent.refine(dbaBase);

        } else {
            progressService.send(pipelineId, "QA_RETRY",
                String.format("API 스펙 결함 수정 중... (%d/%d회)", attempt + 1, MAX_QA_RETRY), fixPct);
            PipelineState apiBase = afterPrd.toBuilder().contextPrompt(baseCtx + apiIssueSection).build();
            apiResult = apiAgent.refine(apiBase);
        }

        PipelineState merged = afterPrd.toBuilder()
                .dbSchema(dbaResult.getDbSchema())
                .apiSpec(apiResult.getApiSpec())
                .retryCount(attempt + 1)
                .build();

        return runQaRetryOnly(merged, attempt + 1, pipelineId);
    }
}
