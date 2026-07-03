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
 * Phase 2 협업 오케스트레이션 그래프
 *
 * 흐름:
 *   Search → [Round 1: PM+PRD+DBA+API 동시 초안]
 *          → [Round 2: 4방향 피드백 교환 후 동시 수정]
 *          → QA 검수
 *
 * Round 1: 각 에이전트가 Phase1 컨텍스트 + Search 결과를 직접 해석하여 초안 작성
 * Round 2: 각 에이전트가 다른 에이전트의 초안을 읽고 불일치를 수정한 최종본 작성
 *          - PM  ← PRD/DBA/API 초안 보고 featureList 보완
 *          - PRD ← PM/DBA/API 초안 보고 요구사항 명세 정렬
 *          - DBA ← PM/PRD/API 초안 보고 스키마 보완
 *          - API ← PM/PRD/DBA 초안 보고 엔드포인트 보완
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollaborativeOrchestrationGraph {

    private final SearchAgent             searchAgent;
    private final PmAgent                 pmAgent;
    private final PrdAgent                prdAgent;
    private final DbaAgent                dbaAgent;
    private final ApiAgent                apiAgent;
    private final QaAgent                 qaAgent;
    private final PipelineProgressService progressService;
    private final AgentRLService          agentRLService;

    private static final int MAX_QA_RETRY = 3;

    public PipelineState run(PipelineState initialState, String pipelineId) {
        log.info("=== Collaborative Phase 2 시작 ===");

        // Step 1: Search — 시장 데이터 수집 (모든 에이전트가 공통으로 사용)
        progressService.send(pipelineId, "SEARCH", "시장 조사 중...", 20);
        PipelineState afterSearch = searchAgent.execute(initialState);

        // Step 2: Round 1 — PM + PRD + DBA + API 동시 초안
        progressService.send(pipelineId, "DRAFT", "4개 에이전트 동시 초안 작성 중...", 38);
        PipelineState afterDraft = runParallelDrafts(afterSearch);

        // Step 3: Round 2 — 4방향 피드백 교환 후 동시 수정
        progressService.send(pipelineId, "COLLAB", "에이전트 간 피드백 교환 및 수정 중...", 62);
        PipelineState afterRevision = runParallelRevision(afterDraft);

        // Step 4: QA 검수
        progressService.send(pipelineId, "QA", "QA 검수 중...", 85);
        PipelineState finalState = runQaWithRetry(afterRevision, 0, pipelineId);

        // RL: QA 결과로 에이전트 temperature 업데이트
        double rawScore   = finalState.getQaQualityScore();
        int    retries    = finalState.getRetryCount();
        double finalScore = Math.max(0.05, rawScore - 0.15 * retries);
        String rlKey = finalState.getSessionId() != null ? finalState.getSessionId() : pipelineId;
        agentRLService.applyQaFeedback(rlKey, finalScore);
        log.info("Agent RL 피드백 — rawScore:{}, retries:{}, finalScore:{}",
                String.format("%.2f", rawScore), retries, String.format("%.2f", finalScore));

        log.info("=== Collaborative Phase 2 완료 ===");
        return finalState;
    }

    // ── Round 1: 4개 에이전트 동시 초안 ──────────────────────────

    private PipelineState runParallelDrafts(PipelineState state) {
        log.info("[Round 1] PM + PRD + DBA + API 동시 초안 작성");

        CompletableFuture<PipelineState> pmFuture  =
                CompletableFuture.supplyAsync(() -> pmAgent.executeDraft(state));
        CompletableFuture<PipelineState> prdFuture =
                CompletableFuture.supplyAsync(() -> prdAgent.executeDraft(state));
        CompletableFuture<PipelineState> dbaFuture =
                CompletableFuture.supplyAsync(() -> dbaAgent.executeDraft(state));
        CompletableFuture<PipelineState> apiFuture =
                CompletableFuture.supplyAsync(() -> apiAgent.executeDraft(state));

        CompletableFuture.allOf(pmFuture, prdFuture, dbaFuture, apiFuture).join();

        PipelineState pm  = pmFuture.join();
        PipelineState prd = prdFuture.join();
        PipelineState dba = dbaFuture.join();
        PipelineState api = apiFuture.join();

        log.info("[Round 1 완료] PM:{}기능 | PRD:{} | DBA:{} | API:{}",
                pm.getFeatureList()  != null ? pm.getFeatureList().size() + "개" : "실패",
                prd.getPrdDocument() != null ? "완료" : "실패",
                dba.getDbSchema()    != null ? "완료" : "실패",
                api.getApiSpec()     != null ? "완료" : "실패");

        // 4개 초안 모두 workspace에 합침 → Round 2에서 서로 참조
        return state.toBuilder()
                .featureList(pm.getFeatureList())
                .dbaInstruction(pm.getDbaInstruction())
                .apiInstruction(pm.getApiInstruction())
                .prdDocument(prd.getPrdDocument())
                .dbSchema(dba.getDbSchema())
                .apiSpec(api.getApiSpec())
                .build();
    }

    // ── Round 2: 4방향 피드백 교환 후 동시 수정 ──────────────────

    private PipelineState runParallelRevision(PipelineState state) {
        log.info("[Round 2] 4방향 피드백 교환 및 동시 수정 — 각 에이전트가 다른 3개 초안을 보고 자신의 결과 수정");

        // 각 에이전트가 workspace의 모든 초안을 읽고 자신의 출력물 수정
        CompletableFuture<PipelineState> pmFuture  =
                CompletableFuture.supplyAsync(() -> pmAgent.refine(state));
        CompletableFuture<PipelineState> prdFuture =
                CompletableFuture.supplyAsync(() -> prdAgent.refine(state));
        CompletableFuture<PipelineState> dbaFuture =
                CompletableFuture.supplyAsync(() -> dbaAgent.refine(state));
        CompletableFuture<PipelineState> apiFuture =
                CompletableFuture.supplyAsync(() -> apiAgent.refine(state));

        CompletableFuture.allOf(pmFuture, prdFuture, dbaFuture, apiFuture).join();

        PipelineState pm  = pmFuture.join();
        PipelineState prd = prdFuture.join();
        PipelineState dba = dbaFuture.join();
        PipelineState api = apiFuture.join();

        log.info("[Round 2 완료] PM:{}기능 | PRD:{} | DBA:{} | API:{}",
                pm.getFeatureList()  != null ? pm.getFeatureList().size() + "개" : "실패",
                prd.getPrdDocument() != null ? "완료" : "실패",
                dba.getDbSchema()    != null ? "완료" : "실패",
                api.getApiSpec()     != null ? "완료" : "실패");

        return state.toBuilder()
                .featureList(   pm.getFeatureList()   != null ? pm.getFeatureList()   : state.getFeatureList())
                .dbaInstruction(pm.getDbaInstruction() != null ? pm.getDbaInstruction() : state.getDbaInstruction())
                .apiInstruction(pm.getApiInstruction() != null ? pm.getApiInstruction() : state.getApiInstruction())
                .prdDocument(   prd.getPrdDocument()  != null ? prd.getPrdDocument()  : state.getPrdDocument())
                .dbSchema(      dba.getDbSchema()     != null ? dba.getDbSchema()     : state.getDbSchema())
                .apiSpec(       api.getApiSpec()      != null ? api.getApiSpec()      : state.getApiSpec())
                .build();
    }

    // ── QA 재시도 루프 ────────────────────────────────────────────

    private PipelineState runQaWithRetry(PipelineState state, int attempt, String pipelineId) {
        if (attempt > 0) {
            progressService.send(pipelineId, "QA_RETRY",
                    String.format("QA 재검수 중... (%d/%d회)", attempt, MAX_QA_RETRY), 87);
        }

        PipelineState qaResult = qaAgent.execute(state);
        boolean failed = qaResult.getLastValidationError() != null
                && !qaResult.getLastValidationError().isBlank();

        if (!failed) {
            log.info("QA 통과 (시도 {})", attempt + 1);
            return qaResult;
        }

        if (attempt >= MAX_QA_RETRY) {
            log.warn("QA 최대 재시도 초과 — 현재 결과로 진행");
            return qaResult.toBuilder()
                    .lastValidationError("")
                    .statusMessage("QA 최대 재시도 초과")
                    .build();
        }

        log.warn("QA 실패 — DBA/API 재수정 ({}/{})", attempt + 1, MAX_QA_RETRY);

        String retryContext = (state.getContextPrompt() != null ? state.getContextPrompt() : "")
                + "\n\n=== QA 지적 결함 (반드시 수정) ===\n"
                + qaResult.getLastValidationError();

        PipelineState retryBase = state.toBuilder()
                .contextPrompt(retryContext)
                .lastValidationError("")
                .retryCount(attempt + 1)
                .build();

        CompletableFuture<PipelineState> dbaFuture =
                CompletableFuture.supplyAsync(() -> dbaAgent.refine(retryBase));
        CompletableFuture<PipelineState> apiFuture =
                CompletableFuture.supplyAsync(() -> apiAgent.refine(retryBase));
        CompletableFuture.allOf(dbaFuture, apiFuture).join();

        PipelineState retried = retryBase.toBuilder()
                .dbSchema(dbaFuture.join().getDbSchema())
                .apiSpec(apiFuture.join().getApiSpec())
                .build();

        return runQaWithRetry(retried, attempt + 1, pipelineId);
    }
}
