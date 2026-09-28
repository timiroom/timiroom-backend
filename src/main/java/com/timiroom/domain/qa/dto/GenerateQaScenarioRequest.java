package com.timiroom.domain.qa.dto;

/** AI 시나리오 생성을 요청할 대상 기능. */
public record GenerateQaScenarioRequest(
        String featureName
) {}
