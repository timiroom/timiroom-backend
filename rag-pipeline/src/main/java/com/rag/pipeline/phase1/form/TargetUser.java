package com.rag.pipeline.phase1.form;

import jakarta.validation.constraints.NotBlank;

/**
 * Step4 - 타겟유저 페르소나 카드 (복수 가능)
 * PmAgent UserPersona 섹션에 직접 주입
 */
public record TargetUser(
    @NotBlank String persona,           // 예: 20대 경영학과 대학생
    @NotBlank String usageEnvironment,  // 예: 사무실 PC, 스프린트 회의 중
    @NotBlank String biggestPainPoint   // 예: 회의 후 내용 복기가 어려움
) {}
