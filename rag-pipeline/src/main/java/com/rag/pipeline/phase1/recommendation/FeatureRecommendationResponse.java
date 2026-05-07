package com.rag.pipeline.phase1.recommendation;

import java.util.List;

public record FeatureRecommendationResponse(List<RecommendedFeature> features) {
    public static FeatureRecommendationResponse empty() {
        return new FeatureRecommendationResponse(List.of());
    }
}
