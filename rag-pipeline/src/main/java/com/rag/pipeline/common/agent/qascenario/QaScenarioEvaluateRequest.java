package com.rag.pipeline.common.agent.qascenario;

import java.util.List;

/** 시나리오가 현재 명세·코드를 근거로 통과할지 판단을 요청하는 구조화된 요청. */
public record QaScenarioEvaluateRequest(
        String model,
        String projectName,
        String featureName,
        String scenarioTitle,
        String given,
        String when,
        String then,
        String apiSpec,
        String dbSchema,
        List<CodeFile> codeFiles
) {
    public QaScenarioEvaluateRequest {
        codeFiles = codeFiles == null ? List.of() : List.copyOf(codeFiles);
    }

    public record CodeFile(String filename, String content) {}
}
