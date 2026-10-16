package com.rag.pipeline.phase1.rl;

public record SearchParams(
        double vectorWeight,
        double keywordWeight,
        double similarityThreshold
) {
    public static SearchParams defaults() {
        return new SearchParams(1.0, 1.0, 0.3);
    }
}
