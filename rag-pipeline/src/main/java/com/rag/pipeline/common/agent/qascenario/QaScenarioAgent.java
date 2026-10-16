package com.rag.pipeline.common.agent.qascenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;

/**
 * QA 테스트 시나리오를 생성하고, 시나리오가 현재 명세·코드를 근거로 통과할지 판단하는 에이전트.
 *
 * evaluate는 코드를 컴파일·실행하지 않는다. 제공된 API_SPEC·DB_SCHEMA와(있다면) 관련
 * 코드 조각만 근거로 논리적으로 추론하며, 근거가 부족하면 PASS/FAIL 대신 INCONCLUSIVE로 답한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QaScenarioAgent {

    private static final String AGENT_NAME = "QA_SCENARIO_AGENT";
    private static final Set<String> SCENARIO_TYPES = Set.of("NORMAL", "EXCEPTION", "BOUNDARY");
    private static final Set<String> VERDICTS = Set.of("PASS", "FAIL", "INCONCLUSIVE");

    private static final String GENERATE_SYSTEM_PROMPT = """
            당신은 소프트웨어 QA 엔지니어 에이전트입니다. 주어진 기능 명세(설명·요구사항)와
            API/DB 명세를 근거로 실행 가능한 테스트 시나리오를 생성하세요.

            각 시나리오는 Given-When-Then 형식으로 작성하고, 다음 세 유형을 각각 최소 1개 이상 포함하세요:
            - NORMAL(정상): 요구사항이 정상적으로 충족되는 경우
            - EXCEPTION(예외): 잘못된 입력이나 실패 조건
            - BOUNDARY(경계): 입력 길이·개수·범위 등 한계값

            명세에 없는 세부사항은 추측하지 말고, 명세에서 확인 가능한 내용을 근거로 작성하세요.

            JSON 외 텍스트는 출력하지 마세요.
            {
              "scenarios": [
                {"type": "NORMAL|EXCEPTION|BOUNDARY", "title": "...", "given": "...", "when": "...", "then": "..."}
              ]
            }
            """;

    private static final String EVALUATE_SYSTEM_PROMPT = """
            당신은 시니어 QA 엔지니어 에이전트입니다. 주어진 테스트 시나리오(Given-When-Then)가
            현재 구현된 코드와 명세를 근거로 통과할지 판단하세요.

            실제로 코드를 실행하지 않습니다. 제공된 코드 조각·API 명세·DB 명세만 근거로
            논리적으로 추론하세요. 근거 없이 추측하지 마세요.

            판정 기준:
            - PASS: 제공된 근거에서 시나리오의 then(예상 결과)이 실제로 충족됨을 확인함
            - FAIL: 제공된 근거에서 given/when 대비 then이 충족되지 않음을 확인함
            - INCONCLUSIVE: 판단에 필요한 코드나 명세가 부족해 확정할 수 없음 — 코드 조각이
              전혀 제공되지 않았다면 명세만으로 단정하지 말고 우선 INCONCLUSIVE를 고려하세요

            JSON 외 텍스트는 출력하지 마세요.
            {
              "verdict": "PASS|FAIL|INCONCLUSIVE",
              "reasoning": "판단 근거를 설명하는 한국어 문장",
              "evidence": ["근거로 사용한 구체적 코드/명세 조각 또는 파일명"]
            }
            """;

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.api-key:}")
    private String foundryApiKey;

    @Value("${app.ai.foundry.responses-url}")
    private String foundryUrl;

    @Value("${app.agent.qa-scenario.model:gpt-5.4-mini}")
    private String defaultModel;

    public QaScenarioGenerateResponse generate(QaScenarioGenerateRequest request) {
        requireApiKey();
        String model = valueOrDefault(request.model(), defaultModel);
        String context = """
                [PROJECT]
                %s

                [FEATURE]
                name: %s
                description: %s
                requirements: %s

                [API_SPEC]
                %s

                [DB_SCHEMA]
                %s
                """.formatted(
                valueOrDefault(request.projectName(), "(없음)"),
                valueOrDefault(request.featureName(), "(없음)"),
                truncate(request.featureDescription(), 2_000),
                truncate(request.requirements(), 3_000),
                truncate(request.apiSpec(), 10_000),
                truncate(request.dbSchema(), 10_000));

        String raw = callFoundry(model, GENERATE_SYSTEM_PROMPT, context);
        return parseGenerateResponse(raw, model);
    }

    public QaScenarioEvaluateResponse evaluate(QaScenarioEvaluateRequest request) {
        requireApiKey();
        String model = valueOrDefault(request.model(), defaultModel);

        StringBuilder files = new StringBuilder();
        for (QaScenarioEvaluateRequest.CodeFile file : request.codeFiles()) {
            files.append("\n### ").append(valueOrDefault(file.filename(), "(이름 없음)")).append("\n")
                    .append(truncate(file.content(), 6_000));
        }
        if (files.isEmpty()) files.append("(관련 코드를 찾지 못했습니다 — 명세만 근거로 판단하세요)");

        String context = """
                [PROJECT]
                %s

                [SCENARIO]
                feature: %s
                title: %s
                given: %s
                when: %s
                then: %s

                [API_SPEC]
                %s

                [DB_SCHEMA]
                %s

                [RELATED_CODE]
                %s
                """.formatted(
                valueOrDefault(request.projectName(), "(없음)"),
                valueOrDefault(request.featureName(), "(없음)"),
                valueOrDefault(request.scenarioTitle(), "(없음)"),
                truncate(request.given(), 1_000),
                truncate(request.when(), 1_000),
                truncate(request.then(), 1_000),
                truncate(request.apiSpec(), 10_000),
                truncate(request.dbSchema(), 10_000),
                truncate(files.toString(), 20_000));

        String raw = callFoundry(model, EVALUATE_SYSTEM_PROMPT, context);
        return parseEvaluateResponse(raw, model);
    }

    private String callFoundry(String model, String systemPrompt, String context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("instructions", systemPrompt);
        payload.put("input", List.of(Map.of("role", "user", "content", context)));
        payload.put("max_output_tokens", 4_000);

        try {
            return restClientBuilder.build()
                    .post()
                    .uri(foundryUrl)
                    .header("Authorization", "Bearer " + foundryApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new IllegalStateException("QA Scenario Agent 호출에 실패했습니다", e);
        }
    }

    private void requireApiKey() {
        if (foundryApiKey == null || foundryApiKey.isBlank()) {
            throw new IllegalStateException("QA Scenario Agent API 키가 설정되지 않았습니다");
        }
    }

    QaScenarioGenerateResponse parseGenerateResponse(String raw, String model) {
        try {
            JsonNode result = objectMapper.readTree(extractJson(extractText(objectMapper.readTree(raw))));
            List<QaScenarioGenerateResponse.GeneratedScenario> scenarios = new ArrayList<>();
            for (JsonNode node : result.path("scenarios")) {
                String type = node.path("type").asText("NORMAL").toUpperCase(Locale.ROOT);
                if (!SCENARIO_TYPES.contains(type)) type = "NORMAL";
                String title = node.path("title").asText("").trim();
                String given = node.path("given").asText("").trim();
                String when = node.path("when").asText("").trim();
                String then = node.path("then").asText("").trim();
                if (!title.isBlank() && !then.isBlank()) {
                    scenarios.add(new QaScenarioGenerateResponse.GeneratedScenario(type, title, given, when, then));
                }
            }
            if (scenarios.isEmpty()) {
                throw new IllegalStateException("QA Scenario Agent가 시나리오를 반환하지 않았습니다");
            }
            log.info("QA Scenario Agent 생성 완료 — scenarios={}", scenarios.size());
            return new QaScenarioGenerateResponse(AGENT_NAME, model, scenarios);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("QA Scenario Agent 응답 파싱에 실패했습니다", e);
        }
    }

    QaScenarioEvaluateResponse parseEvaluateResponse(String raw, String model) {
        try {
            JsonNode result = objectMapper.readTree(extractJson(extractText(objectMapper.readTree(raw))));
            String verdict = result.path("verdict").asText("INCONCLUSIVE").toUpperCase(Locale.ROOT);
            if (!VERDICTS.contains(verdict)) verdict = "INCONCLUSIVE";
            String reasoning = valueOrDefault(result.path("reasoning").asText(), "판정 근거가 제공되지 않았습니다.");
            List<String> evidence = new ArrayList<>();
            for (JsonNode node : result.path("evidence")) {
                String text = node.asText("").trim();
                if (!text.isBlank()) evidence.add(text);
            }
            log.info("QA Scenario Agent 평가 완료 — verdict={}", verdict);
            return new QaScenarioEvaluateResponse(AGENT_NAME, model, verdict, reasoning, evidence);
        } catch (Exception e) {
            throw new IllegalStateException("QA Scenario Agent 응답 파싱에 실패했습니다", e);
        }
    }

    private String extractText(JsonNode root) {
        for (JsonNode item : root.path("output")) {
            if (!"message".equals(item.path("type").asText())) continue;
            for (JsonNode block : item.path("content")) {
                if ("output_text".equals(block.path("type").asText())) {
                    return block.path("text").asText();
                }
            }
        }
        throw new IllegalStateException("QA Scenario Agent 응답 본문이 없습니다");
    }

    private String extractJson(String text) {
        String clean = valueOrDefault(text, "").replace("```json", "").replace("```", "").trim();
        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalStateException("QA Scenario Agent JSON을 찾을 수 없습니다");
        return clean.substring(start, end + 1);
    }

    private String truncate(String text, int limit) {
        if (text == null || text.isBlank()) return "(없음)";
        return text.length() <= limit ? text : text.substring(0, limit) + "\n...[truncated]";
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
