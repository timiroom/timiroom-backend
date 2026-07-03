package com.rag.pipeline.phase1.form;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Step5 - 커스텀 기능 (사용자 직접 추가, MoSCoW 우선순위)
 */
public record CustomFeature(
    @NotNull MoSCoW priority,
    @JsonAlias("name") @NotBlank String featureName,
    String description
) {}
