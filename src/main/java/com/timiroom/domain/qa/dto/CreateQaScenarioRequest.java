package com.timiroom.domain.qa.dto;

/** 사용자가 직접 작성하는 QA 시나리오. */
public record CreateQaScenarioRequest(
        String featureName,
        String type,
        String title,
        String given,
        String when,
        String then
) {}
