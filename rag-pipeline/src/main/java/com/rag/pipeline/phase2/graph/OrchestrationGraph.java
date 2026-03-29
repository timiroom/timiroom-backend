package com.rag.pipeline.phase2.graph;

import com.rag.pipeline.phase2.agent.*;
import com.rag.pipeline.phase2.state.PipelineState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Phase 2 전체 워크플로우 그래프
 *
 * 흐름:
 *   PM 에이전트
 *     → DBA 에이전트 + API 에이전트 (병렬)
 *     → Fan-in 집계
 *     → QA 에이전트 (논리적 정합성 검수)
 *     → QA 실패 시 결함 재주입 후 재시도 (최대 5회)
 *     → 재시도 2회 이상부터 QA 기준 완화
 *     → Phase 3 진입
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrchestrationGraph {

    private final SearchAgent searchAgent;
    private final PmAgent  pmAgent;
    private final DbaAgent dbaAgent;
    private final ApiAgent apiAgent;
    private final QaAgent  qaAgent;
    private final PrdAgent prdAgent;

    private static final int MAX_QA_RETRY = 5;

    /**
     * Phase 2 전체 실행
     */
    public PipelineState execute(String userQuery, String contextPrompt) {
        log.info("=== Phase 2 오케스트레이션 시작 ===");

        PipelineState initialState = PipelineState.builder()
                .userQuery(userQuery)
                .contextPrompt(contextPrompt)
                .build();

        // STEP 1 — Search 에이전트
        log.info("Search 에이전트 실행 중...");
        PipelineState afterSearch = searchAgent.execute(initialState);

        // STEP 2 — PM 에이전트
        log.info("PM 에이전트 실행 중...");
        PipelineState afterPm = pmAgent.execute(afterSearch);  // ← 1번 버그 수정도 포함

        // STEP 3 — PRD + DBA/API rollback 포함
        // runPrdWithRollback이 DBA/API 결과까지 포함해서 반환
        log.info("PRD + DBA/API 에이전트 실행 중...");
        PipelineState afterPrdDbaApi = runPrdWithRollback(afterPm);

        // STEP 4 — QA만 재시도 (DBA/API 재실행 없음)
        log.info("QA 에이전트 실행 중...");
        PipelineState finalState = runQaRetryOnly(afterPrdDbaApi, 0);

        log.info("=== Phase 2 완료 ===");
        return finalState;
    }

    /**
     * DBA + API 병렬 실행 → Fan-in → QA 검수
     *
     * 재시도 전략:
     *   0~1회: 일반 QA 검수 (엄격)
     *   2회~:  QA 완화 모드 (치명적 결함만 검수)
     *   5회 초과: Phase 3로 위임
     */
    private PipelineState runWithQaRetry(PipelineState pmState, int attempt) {

        log.info("DBA / API 에이전트 병렬 실행 중... (시도 {})", attempt + 1);

        // Fan-out — DBA + API 병렬 실행
        CompletableFuture<PipelineState> dbaFuture =
                CompletableFuture.supplyAsync(() -> dbaAgent.execute(pmState));
        CompletableFuture<PipelineState> apiFuture =
                CompletableFuture.supplyAsync(() -> apiAgent.execute(pmState));

        // Fan-in — 두 결과 병합
        PipelineState dbaResult = dbaFuture.join();
        PipelineState apiResult = apiFuture.join();

        // 재시도 횟수를 State에 담아서 QA 에이전트에 전달
        PipelineState merged = pmState.toBuilder()
                .dbSchema(dbaResult.getDbSchema())
                .apiSpec(apiResult.getApiSpec())
                .retryCount(attempt)
                .build();

        // QA 에이전트 실행 (attempt 값으로 엄격도 조절)
        log.info("QA 에이전트 실행 중... (재시도 {}회 — {})",
                attempt,
                attempt >= 2 ? "완화 모드" : "일반 모드");
        PipelineState qaResult = qaAgent.execute(merged);

        boolean qaFailed = qaResult.getLastValidationError() != null
                && !qaResult.getLastValidationError().isBlank();

        // QA 통과 → 완료
        if (!qaFailed) {
            log.info("QA 검수 통과 (시도 {})", attempt + 1);
            return qaResult;
        }

        // 최대 재시도 초과 → Phase 3로 위임
        if (attempt >= MAX_QA_RETRY) {
            log.warn("QA 최대 재시도 초과 ({}) — Phase 3로 위임", MAX_QA_RETRY);
            return qaResult.toBuilder()
                    .lastValidationError("")
                    .statusMessage("QA 최대 재시도 초과 — Phase 3 형식 검증으로 위임")
                    .build();
        }

        // QA 실패 → 결함 재주입
        log.warn("QA 검수 실패 — 재시도 ({}/{}) 결함 재주입", attempt + 1, MAX_QA_RETRY);

        String validationError = qaResult.getLastValidationError();

        String retryContext = pmState.getContextPrompt()
                + "\n\n"
                + "=== 이전 설계의 치명적 결함 (반드시 수정) ===\n"
                + validationError
                + "\n\n"
                + "위 결함을 모두 수정하여 완전한 설계를 다시 생성하세요.\n"
                + "누락된 테이블, 컬럼, API 엔드포인트를 반드시 포함하세요.";

        PipelineState retryState = pmState.toBuilder()
                .contextPrompt(retryContext)
                .lastValidationError("")
                .retryCount(attempt + 1)
                .build();

        return runWithQaRetry(retryState, attempt + 1);
    }

    private PipelineState runPrdWithRollback(PipelineState pmState) {
        PipelineState current = pmState;

        for (int attempt = 0; attempt <= 2; attempt++) {
            log.info("PRD 에이전트 실행 중... (시도 {})", attempt + 1);
            PipelineState afterPrd = prdAgent.execute(current);

            // DBA + API 병렬 실행해서 피드백 수집
            CompletableFuture<PipelineState> dbaFuture =
                    CompletableFuture.supplyAsync(() -> dbaAgent.execute(afterPrd));
            CompletableFuture<PipelineState> apiFuture =
                    CompletableFuture.supplyAsync(() -> apiAgent.execute(afterPrd));

            PipelineState dbaResult = dbaFuture.join();
            PipelineState apiResult = apiFuture.join();

            boolean dbaHasFeedback = dbaResult.getPrdFeedbackFromDba() != null
                    && !dbaResult.getPrdFeedbackFromDba().isBlank();
            boolean apiHasFeedback = apiResult.getPrdFeedbackFromApi() != null
                    && !apiResult.getPrdFeedbackFromApi().isBlank();

            // 피드백 없으면 통과
            if (!dbaHasFeedback && !apiHasFeedback) {
                log.info("PRD ↔ DBA/API 검증 통과 (시도 {})", attempt + 1);
                return afterPrd.toBuilder()
                        .dbSchema(dbaResult.getDbSchema())
                        .apiSpec(apiResult.getApiSpec())
                        .build();
            }

            // 피드백 있으면 rollback
            if (attempt < 2) {
                log.warn("PRD rollback #{} — DBA피드백: {}, API피드백: {}",
                        attempt + 1,
                        dbaResult.getPrdFeedbackFromDba(),
                        apiResult.getPrdFeedbackFromApi());

                current = afterPrd.toBuilder()
                        .prdFeedbackFromDba(dbaResult.getPrdFeedbackFromDba())
                        .prdFeedbackFromApi(apiResult.getPrdFeedbackFromApi())
                        .rollbackCount(attempt + 1)
                        .build();
            } else {
                // 최대 rollback 초과 → 그냥 진행
                log.warn("PRD rollback 최대 횟수 초과 — 현재 결과로 진행");
                return afterPrd.toBuilder()
                        .dbSchema(dbaResult.getDbSchema())
                        .apiSpec(apiResult.getApiSpec())
                        .build();
            }
        }

        return current;
    }
    /**
     * QA만 재시도 — DBA/API는 재실행하지 않음
     * runPrdWithRollback에서 이미 DBA/API가 실행됐기 때문
     */
    private PipelineState runQaRetryOnly(PipelineState state, int attempt) {

        log.info("QA 에이전트 실행 중... (시도 {})", attempt + 1);
        PipelineState qaResult = qaAgent.execute(state);

        boolean qaFailed = qaResult.getLastValidationError() != null
                && !qaResult.getLastValidationError().isBlank();

        // QA 통과
        if (!qaFailed) {
            log.info("QA 검수 통과 (시도 {})", attempt + 1);
            return qaResult;
        }

        // 최대 재시도 초과
        if (attempt >= MAX_QA_RETRY) {
            log.warn("QA 최대 재시도 초과 ({}) — Phase 3로 위임", MAX_QA_RETRY);
            return qaResult.toBuilder()
                    .lastValidationError("")
                    .statusMessage("QA 최대 재시도 초과 — Phase 3 형식 검증으로 위임")
                    .build();
        }

        // QA 실패 → DBA/API만 재실행 후 QA 재시도
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

        // DBA + API 병렬 재실행
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

        return runQaRetryOnly(merged, attempt + 1);
    }
}