package com.rag.pipeline.phase1.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Step5 - 커스텀 기능 (사용자 직접 추가, MoSCoW 우선순위)
 */
public record CustomFeature(
    @NotNull MoSCoW priority,     // MUST | SHOULD | COULD | WONT
    @NotBlank String featureName, // 예: 회의록 자동 생성
    String description            // 기능 설명 + 필요한 라이브러리 힌트 (nullable)
) {}
