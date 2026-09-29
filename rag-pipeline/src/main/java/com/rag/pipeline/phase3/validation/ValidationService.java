package com.rag.pipeline.phase3.validation;

import com.rag.pipeline.phase2.state.PipelineState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Phase 3 — 검증 오케스트레이터
 *
 * 처리 흐름:
 *   1. PipelineState → GenerationResult 변환
 *   2. SchemaValidator로 검증 실행
 *   3. 결과를 PipelineState에 반영하여 반환
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValidationService {

    private final SchemaValidator schemaValidator;

    /**
     * 검증 실행
     *
     * @param state Phase 2 결과가 담긴 State
     * @return 검증 결과가 반영된 새 State
     */
    public PipelineState validate(PipelineState state) {
        log.info("=== Phase 3 검증 시작 === (retry: {})", state.getRetryCount());

        // PipelineState → GenerationResult 변환
        GenerationResult result = GenerationResult.builder()
                .featureList(state.getFeatureList())
                .dbSchema(state.getDbSchema())
                .apiSpec(state.getApiSpec())
                .build();

        // 검증 실행
        SchemaValidator.ValidationResult validationResult =
                schemaValidator.validate(result);

        if (validationResult.isSuccess()) {
            log.info("=== Phase 3 검증 통과 ===");
            return state.toBuilder()
                    .validated(true)
                    .statusMessage("Phase 3 완료 — 검증 통과")
                    .build();
        } else {
            log.warn("=== Phase 3 검증 실패 === 오류: {}",
                    validationResult.errorsToString());
            return state.toBuilder()
                    .validated(false)
                    .lastValidationError(validationResult.errorsToString())
                    .statusMessage("Phase 3 검증 실패 — 재시도 필요")
                    .build();
        }
    }
}