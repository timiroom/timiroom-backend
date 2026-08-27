package com.rag.pipeline.phase3.validation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Phase 3 검증 대상 DTO
 *
 * Phase 2에서 생성된 결과물을 담는 객체입니다.
 * Hibernate Validator 어노테이션으로 비즈니스 규칙을 정의합니다.
 */
@Getter
@Builder
public class GenerationResult {

    /** PM 에이전트가 도출한 기능 목록 — 비어있으면 안 됨 */
    @NotNull(message = "기능 목록은 필수입니다")
    @NotEmpty(message = "기능 목록이 비어있습니다")
    private final List<String> featureList;

    /** DBA 에이전트가 생성한 DB 스키마 JSON — 비어있으면 안 됨 */
    @NotBlank(message = "DB 스키마는 필수입니다")
    private final String dbSchema;

    /** API 에이전트가 생성한 API 스펙 JSON — 비어있으면 안 됨 */
    @NotBlank(message = "API 스펙은 필수입니다")
    private final String apiSpec;
}