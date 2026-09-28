package com.timiroom.domain.qa.dto;

import com.timiroom.domain.qa.entity.QaScenario;

import java.time.LocalDateTime;
import java.util.List;

public record QaScenarioResponse(
        Long id,
        String featureName,
        String type,
        String title,
        String given,
        String when,
        String then,
        String source,
        String verdict,
        String reasoning,
        List<String> evidence,
        LocalDateTime evaluatedAt,
        LocalDateTime createdAt
) {
    public static QaScenarioResponse from(QaScenario scenario, List<String> evidence) {
        return new QaScenarioResponse(
                scenario.getId(),
                scenario.getFeatureName(),
                scenario.getType().name(),
                scenario.getTitle(),
                scenario.getGiven(),
                scenario.getWhenStep(),
                scenario.getThenResult(),
                scenario.getSource().name(),
                scenario.getVerdict() == null ? null : scenario.getVerdict().name(),
                scenario.getReasoning(),
                evidence,
                scenario.getEvaluatedAt(),
                scenario.getCreatedAt());
    }
}
