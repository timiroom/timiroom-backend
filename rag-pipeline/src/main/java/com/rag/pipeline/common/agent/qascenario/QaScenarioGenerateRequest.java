package com.rag.pipeline.common.agent.qascenario;

/** 기능 명세를 근거로 QA 테스트 시나리오 생성을 요청하는 구조화된 요청. */
public record QaScenarioGenerateRequest(
        String model,
        String projectName,
        String featureName,
        String featureDescription,
        String requirements,
        String apiSpec,
        String dbSchema
) {}
