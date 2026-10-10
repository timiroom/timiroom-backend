package com.rag.pipeline.phase1.recommendation;

public record TechStackRequest(
    String projectName,
    String projectDescription,
    String platform
) {}
