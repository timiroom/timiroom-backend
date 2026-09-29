package com.rag.pipeline.common.agent.qascenario;

import java.util.List;

/** QA 시나리오 평가 에이전트의 응답. 실제 코드를 실행하지 않고 근거 기반으로 추론한 판정이다. */
public record QaScenarioEvaluateResponse(
        String agent,
        String model,
        String verdict,
        String reasoning,
        List<String> evidence
) {
    public QaScenarioEvaluateResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
