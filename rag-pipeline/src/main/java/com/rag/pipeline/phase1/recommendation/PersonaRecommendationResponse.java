package com.rag.pipeline.phase1.recommendation;

import java.util.List;

public record PersonaRecommendationResponse(List<RecommendedPersona> personas) {
    public static PersonaRecommendationResponse empty() {
        return new PersonaRecommendationResponse(List.of());
    }
}
