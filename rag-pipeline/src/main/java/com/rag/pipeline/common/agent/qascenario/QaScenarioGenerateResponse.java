package com.rag.pipeline.common.agent.qascenario;

import java.util.List;

/** QA 시나리오 생성 에이전트의 응답. */
public record QaScenarioGenerateResponse(
        String agent,
        String model,
        List<GeneratedScenario> scenarios
) {
    public QaScenarioGenerateResponse {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
    }

    public record GeneratedScenario(
            String type,
            String title,
            String given,
            String when,
            String then
    ) {}
}
