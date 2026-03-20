package com.rag.pipeline.phase3.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Phase 3 — 검증 컴포넌트
 *
 * 두 단계로 검증합니다:
 *   1. 포맷 검증 — dbSchema, apiSpec이 유효한 JSON인지 확인
 *   2. 비즈니스 규칙 검증 — Hibernate Validator 어노테이션 기반
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaValidator {

    private final Validator     validator;
    private final ObjectMapper  objectMapper;

    /**
     * 검증 실행
     *
     * @param result Phase 2 결과물
     * @return 검증 결과 (성공 여부 + 실패 원인 목록)
     */
    public ValidationResult validate(GenerationResult result) {
        List<String> errors = new ArrayList<>();

        // 1단계 — 포맷 검증 (JSON 유효성)
        errors.addAll(validateJsonFormat(result.getDbSchema(),  "DB 스키마"));
        errors.addAll(validateJsonFormat(result.getApiSpec(),   "API 스펙"));

        // 2단계 — 비즈니스 규칙 검증 (Hibernate Validator)
        Set<ConstraintViolation<GenerationResult>> violations = validator.validate(result);
        for (ConstraintViolation<GenerationResult> v : violations) {
            errors.add(v.getPropertyPath() + ": " + v.getMessage());
        }

        if (errors.isEmpty()) {
            log.info("검증 통과");
            return ValidationResult.success();
        } else {
            log.warn("검증 실패 — {} 개 오류: {}", errors.size(), errors);
            return ValidationResult.failure(errors);
        }
    }

    /** JSON 포맷 유효성 검사 */
    private List<String> validateJsonFormat(String json, String fieldName) {
        if (json == null || json.isBlank()) {
            return List.of(fieldName + ": 값이 비어있습니다");
        }
        try {
            objectMapper.readTree(json);
            return List.of();
        } catch (Exception e) {
            return List.of(fieldName + ": 유효하지 않은 JSON 형식 — " + e.getMessage());
        }
    }

    // ── 검증 결과 DTO ─────────────────────────────────────────────

    @Getter
    @AllArgsConstructor
    public static class ValidationResult {

        private final boolean      success;
        private final List<String> errors;

        public static ValidationResult success() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult failure(List<String> errors) {
            return new ValidationResult(false, errors);
        }

        public String errorsToString() {
            return String.join("\n", errors);
        }
    }
}