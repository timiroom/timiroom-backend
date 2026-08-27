package com.rag.pipeline.phase1.recommendation;

public record RecommendedFeature(
    String priority,
    String featureName,
    String description
) {}
