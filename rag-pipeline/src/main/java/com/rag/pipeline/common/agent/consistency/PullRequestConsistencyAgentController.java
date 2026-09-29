package com.rag.pipeline.common.agent.consistency;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 백엔드 GitHub 연동에서 호출하는 전용 PR 정합성 에이전트 API. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agents/pr-consistency")
public class PullRequestConsistencyAgentController {

    private final PullRequestConsistencyAgent agent;

    @PostMapping("/review")
    public PullRequestConsistencyAgentResponse review(@RequestBody PullRequestConsistencyAgentRequest request) {
        return agent.review(request);
    }
}
