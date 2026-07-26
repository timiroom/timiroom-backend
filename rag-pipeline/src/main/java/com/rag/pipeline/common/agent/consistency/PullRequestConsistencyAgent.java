package com.rag.pipeline.common.agent.consistency;

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
import java.util.Map;
import java.util.Set;

/**
 * PR diff를 최신 API_SPEC·DB_SCHEMA와 의미 단위로 대조하는 전용 AI 에이전트.
 *
 * 규칙 엔진과 달리 단순 문자열 존재 여부가 아니라 HTTP 메서드·경로·데이터 계약,
 * 테이블·컬럼·관계·마이그레이션 의도를 함께 검토한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PullRequestConsistencyAgent {

    private static final String AGENT_NAME = "PR_CONSISTENCY_AGENT";
    private static final Set<String> SEVERITIES = Set.of("PASS", "INFO", "WARNING");
    private static final String SYSTEM_PROMPT = """
            당신은 PR 구현과 설계 명세의 정합성을 검증하는 시니어 소프트웨어 아키텍트 에이전트입니다.
            제공된 PR 메타데이터, 변경 파일 diff, 최신 API_SPEC, 최신 DB_SCHEMA만 근거로 판단하세요.

            검증 기준:
            1. 추가·수정·삭제된 API의 HTTP 메서드, 경로, 요청/응답 계약이 API_SPEC과 일치하는지 확인합니다.
            2. 엔티티·마이그레이션·SQL 변경의 테이블, 컬럼, 타입, 관계가 DB_SCHEMA와 일치하는지 확인합니다.
            3. API 변경과 DB 변경이 함께 필요한데 한쪽만 변경된 구체적 증거가 있는지 확인합니다.
            4. 삭제·이름 변경·호환성 파괴 가능성을 확인하되 diff에 없는 사실을 추측하지 않습니다.
            5. 명세가 없거나 diff가 잘려 확정할 수 없으면 오판하지 말고 INFO 또는 근거가 분명한 WARNING으로 기록합니다.
            6. 기존 API_SPEC·DB_SCHEMA만으로 새 구현을 지원할 수 있다면 해당 레이어의 diff가 없다는 이유만으로 경고하지 않습니다.

            WARNING 금지 조건:
            - 새 API가 기존 테이블과 컬럼만 사용 가능한데 DB 마이그레이션이 없다는 이유만으로 경고
            - 변경 파일 목록에 특정 레이어가 없다는 사실만으로 불일치를 추측
            - diff에 요청·응답 DTO 전체가 없어서 확인할 수 없다는 이유만으로 계약 불일치를 단정

            명확한 WARNING 예시:
            - diff에 추가된 HTTP 메서드+경로 조합이 API_SPEC에 없거나 다른 메서드로만 정의됨
            - diff에 추가·변경된 테이블 또는 컬럼이 DB_SCHEMA와 직접 충돌함
            - 한 레이어의 변경이 다른 레이어의 기존 명세와 호환되지 않는 구체적 근거가 있음

            severity 기준:
            - PASS: diff와 명세에서 일치 근거를 확인함
            - INFO: 관련 변경이 없거나 자료만으로 확정할 수 없음
            - WARNING: diff와 명세 사이에 구체적인 불일치 또는 필수 명세 누락이 있음

            JSON 외 텍스트는 출력하지 마세요.
            {
              "passed": true,
              "summary": "전체 판정 요약 한 문장",
              "findings": [
                {
                  "severity": "PASS|INFO|WARNING",
                  "area": "API|DB|Cross-layer",
                  "message": "diff와 명세의 근거가 드러나는 짧은 한국어 설명"
                }
              ]
            }
            """;

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.api-key:}")
    private String foundryApiKey;

    @Value("${app.ai.foundry.responses-url}")
    private String foundryUrl;

    @Value("${app.agent.pr-consistency.model:gpt-5.4-mini}")
    private String defaultModel;

    public PullRequestConsistencyAgentResponse review(PullRequestConsistencyAgentRequest request) {
        if (foundryApiKey == null || foundryApiKey.isBlank()) {
            throw new IllegalStateException("Consistency Agent API 키가 설정되지 않았습니다");
        }
        String model = valueOrDefault(request.model(), defaultModel);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("instructions", SYSTEM_PROMPT);
        payload.put("input", List.of(Map.of("role", "user", "content", buildContext(request))));
        payload.put("max_output_tokens", 4_000);

        try {
            String raw = restClientBuilder.build()
                    .post()
                    .uri(foundryUrl)
                    .header("Authorization", "Bearer " + foundryApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            PullRequestConsistencyAgentResponse response = parseResponse(raw, model);
            log.info("PR Consistency Agent 완료 — repo={}, pr={}, passed={}, findings={}",
                    request.repository(), request.pullNumber(), response.passed(), response.findings().size());
            return response;
        } catch (Exception e) {
            throw new IllegalStateException("PR Consistency Agent 검증에 실패했습니다", e);
        }
    }

    String buildContext(PullRequestConsistencyAgentRequest request) {
        StringBuilder files = new StringBuilder();
        for (PullRequestConsistencyAgentRequest.ChangedFile file : request.changedFiles()) {
            files.append("\n### ").append(valueOrDefault(file.filename(), "(이름 없음)"))
                    .append(" [").append(valueOrDefault(file.status(), "unknown")).append("]")
                    .append(" +").append(file.additions()).append(" -").append(file.deletions()).append("\n")
                    .append(truncate(file.patch(), 6_000));
        }
        if (files.isEmpty()) files.append("(변경 파일 없음)");

        return """
                [PR]
                repository: %s
                number: %d
                title: %s
                body: %s
                branch: %s -> %s

                [API_SPEC]
                %s

                [DB_SCHEMA]
                %s

                [CHANGED_FILES]
                %s
                """.formatted(
                valueOrDefault(request.repository(), "(없음)"),
                request.pullNumber(),
                valueOrDefault(request.title(), "(없음)"),
                truncate(request.body(), 3_000),
                valueOrDefault(request.headRef(), "(없음)"),
                valueOrDefault(request.baseRef(), "(없음)"),
                truncate(request.apiSpec(), 14_000),
                truncate(request.dbSchema(), 14_000),
                truncate(files.toString(), 30_000));
    }

    PullRequestConsistencyAgentResponse parseResponse(String raw, String model) throws Exception {
        JsonNode envelope = objectMapper.readTree(raw);
        String outputText = extractText(envelope);
        JsonNode result = objectMapper.readTree(extractJson(outputText));

        List<PullRequestConsistencyAgentResponse.Finding> findings = new ArrayList<>();
        for (JsonNode finding : result.path("findings")) {
            String severity = finding.path("severity").asText("INFO").toUpperCase(Locale.ROOT);
            if (!SEVERITIES.contains(severity)) severity = "INFO";
            String area = valueOrDefault(finding.path("area").asText(), "Agent");
            String message = finding.path("message").asText("").trim();
            if (!message.isBlank()) {
                findings.add(new PullRequestConsistencyAgentResponse.Finding(severity, area, message));
            }
        }
        if (findings.isEmpty()) {
            throw new IllegalStateException("Consistency Agent가 구조화된 finding을 반환하지 않았습니다");
        }
        boolean passed = findings.stream().noneMatch(finding -> "WARNING".equals(finding.severity()));
        String summary = valueOrDefault(result.path("summary").asText(), passed
                ? "명세와 구현 사이에서 구체적인 불일치를 찾지 못했습니다."
                : "명세와 구현 사이의 확인이 필요한 불일치를 발견했습니다.");
        return new PullRequestConsistencyAgentResponse(AGENT_NAME, model, passed, summary, List.copyOf(findings));
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
        throw new IllegalStateException("Consistency Agent 응답 본문이 없습니다");
    }

    private String extractJson(String text) {
        String clean = valueOrDefault(text, "").replace("```json", "").replace("```", "").trim();
        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalStateException("Consistency Agent JSON을 찾을 수 없습니다");
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
