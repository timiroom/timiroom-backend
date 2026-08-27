package com.rag.pipeline.common.agent.consistency;

import java.util.List;

/** 전용 Consistency Agent의 구조화된 판정 결과. */
public record PullRequestConsistencyAgentResponse(
        String agent,
        String model,
        boolean passed,
        String summary,
        List<Finding> findings
) {
    public record Finding(
            String severity,
            String area,
            String message
    ) {}
}
