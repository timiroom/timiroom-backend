package com.rag.pipeline.phase1.form;

import java.util.List;

/**
 * Step5 - 기능정의
 * 공통 기능(체크박스) + 커스텀 기능(MoSCoW)
 */
public record FeatureDefinition(
    List<CommonFeature> commonFeatures,
    List<CustomFeature> customFeatures
) {}
