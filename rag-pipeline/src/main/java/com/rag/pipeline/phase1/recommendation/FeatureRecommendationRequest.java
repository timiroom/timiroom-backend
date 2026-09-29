package com.rag.pipeline.phase1.recommendation;

import com.rag.pipeline.phase1.form.ProblemDefinition;
import com.rag.pipeline.phase1.form.TargetUser;
import java.util.List;

public record FeatureRecommendationRequest(
    String projectName,
    String projectDescription,
    String platform,
    List<String> techStack,
    ProblemDefinition problemDefinition,
    List<TargetUser> targetUsers
) {}
