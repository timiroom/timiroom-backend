package com.rag.pipeline.phase1.recommendation;

public record RecommendedPersona(
    String persona,
    String usageEnvironment,
    String biggestPainPoint
) {}
