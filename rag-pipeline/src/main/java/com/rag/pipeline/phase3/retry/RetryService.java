package com.rag.pipeline.phase3.retry;

import com.rag.pipeline.phase2.graph.OrchestrationGraph;
import com.rag.pipeline.phase2.state.PipelineState;
import com.rag.pipeline.phase3.validation.ValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Phase 3 — 재시도 서비스
 *
 * 처리 흐름:
 *   1. 검증 실패 시 실패 원인을 프롬프트에 재주입
 *   2. Phase 2 에이전트 재실행
 *   3. 최대 재시도 횟수 초과 시 Human-in-the-Loop 상태로 전환
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetryService {

    private final OrchestrationGraph orchestrationGraph;
    private final ValidationService  validationService;

    @Value("${app.validation.max-retry:3}")
    private int maxRetry;

    /**
     * 재시도 실행
     *
     * @param state 검증 실패한 State
     * @return 재시도 후 최종 State
     */
    public PipelineState retry(PipelineState state) {
        int currentRetry = state.getRetryCount() + 1;

        // 최대 재시도 횟수 초과 → Human-in-the-Loop
        if (currentRetry > maxRetry) {
            log.warn("최대 재시도 횟수 초과 ({}/{}) — Human-in-the-Loop 전환",
                    currentRetry, maxRetry);
            return state.toBuilder()
                    .statusMessage("Human-in-the-Loop 필요 — 관리자 검토 요청")
                    .build();
        }

        log.info("재시도 시작 ({}/{}) — 실패 원인 재주입", currentRetry, maxRetry);

        // 에러 컨텍스트를 포함한 새 프롬프트 생성
        String retryPrompt = buildRetryPrompt(
                state.getContextPrompt(),
                state.getLastValidationError()
        );

        // Phase 2 재실행 (에러 컨텍스트 포함)
        PipelineState retryState = orchestrationGraph.execute(
                state.getUserQuery(),
                retryPrompt
        );

        // retry_count 증가
        retryState = retryState.toBuilder()
                .retryCount(currentRetry)
                .build();

        // 재검증
        PipelineState validatedState = validationService.validate(retryState);

        if (validatedState.isValidated()) {
            log.info("재시도 성공 ({}/{})", currentRetry, maxRetry);
            return validatedState;
        }

        // 아직 실패면 재귀적으로 다시 재시도
        return retry(validatedState);
    }

    /**
     * 에러 컨텍스트를 포함한 재시도 프롬프트 생성
     *
     * 단순 재시도 (BAD)  → 같은 실수 반복
     * 에러 재주입 (GOOD) → AI가 원인을 알고 수정
     */
    private String buildRetryPrompt(String originalPrompt, String validationError) {
        return originalPrompt
                + "\n\n[이전 시도 실패 원인]\n"
                + validationError
                + "\n\n위 오류를 반드시 수정해서 다시 생성해주세요.";
    }
}
