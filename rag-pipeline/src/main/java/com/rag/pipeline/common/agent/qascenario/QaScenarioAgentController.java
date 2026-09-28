package com.rag.pipeline.common.agent.qascenario;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 백엔드 QA 도메인에서 호출하는 전용 QA 시나리오 에이전트 API. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/agents/qa-scenario")
public class QaScenarioAgentController {

    private final QaScenarioAgent agent;

    @PostMapping("/generate")
    public QaScenarioGenerateResponse generate(@RequestBody QaScenarioGenerateRequest request) {
        return agent.generate(request);
    }

    @PostMapping("/evaluate")
    public QaScenarioEvaluateResponse evaluate(@RequestBody QaScenarioEvaluateRequest request) {
        return agent.evaluate(request);
    }
}
