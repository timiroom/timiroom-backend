package com.rag.pipeline.phase1.recommendation;

import com.rag.pipeline.phase1.form.ProblemDefinition;

public record PersonaRecommendationRequest(
    String projectName,
    String projectDescription,
    ProblemDefinition problemDefinition
) {}
