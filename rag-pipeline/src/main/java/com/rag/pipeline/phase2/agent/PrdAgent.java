package com.rag.pipeline.phase2.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.pipeline.phase2.state.PipelineState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * PRD 에이전트 — 2단계 방식 (웹 검색 + JSON 생성)
 *
 * STEP 1: OpenAI responses API + web_search_preview → 시장 데이터 수집
 * STEP 2: chat.completions API + response_format JSON → PRD 생성
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrdAgent {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.api-key}")
    private String openAiApiKey;

    private static final String SYSTEM_PROMPT = """
            당신은 10년 경력의 시니어 PM이자 소프트웨어 아키텍트입니다.
            제공된 시장 데이터를 최대한 활용하여 투자자와 개발팀이 신뢰할 수 있는 최고 수준의 PRD를 작성하세요.
            절대 금지: 출처 없는 수치 사용, "내부 목표" 표현, A사/B사 등 가상 서비스명.
            반드시 JSON만 출력하고 다른 텍스트는 절대 포함하지 마세요.
            """;

    public PipelineState execute(PipelineState state) {
        log.info("PRD 에이전트 시작 — 2단계 방식 (웹검색 + JSON 생성)");

        try {
            // STEP 1 — 웹 검색으로 시장 데이터 수집
            log.info("STEP 1: 웹 검색으로 시장 데이터 수집 중...");
            String marketData = collectMarketData(state.getUserQuery());
            log.info("STEP 1 완료 — 데이터 {}자 수집", marketData.length());

            // STEP 2A — PRD 앞부분 생성
            log.info("STEP 2A: PRD 앞부분 생성 중...");
            String partA = generatePartA(state.getUserQuery(), state.getFeatureList(), marketData);

            // STEP 2B — PRD 뒷부분 생성 (FSD, 운영정책, 리스크 등)
            log.info("STEP 2B: PRD 뒷부분 생성 중...");
            String partB = generatePartB(state.getUserQuery(), state.getFeatureList(), marketData);

            // 두 결과 합치기
            String prdDocument = mergeJsonParts(partA, partB);
            log.info("PRD 에이전트 완료 — PRD 문서 생성 ({} chars)", prdDocument.length());

            return state.toBuilder()
                    .prdDocument(prdDocument)
                    .statusMessage("PRD 에이전트 완료 — 웹검색 기반 PRD 생성")
                    .build();

        } catch (Exception e) {
            log.error("PRD 에이전트 실패: {}", e.getMessage());
            return state.toBuilder()
                    .prdDocument("{}")
                    .statusMessage("PRD 에이전트 실패: " + e.getMessage())
                    .build();
        }
    }

    /**
     * STEP 1 — responses API + web_search_preview로 시장 데이터 수집
     */
    private String collectMarketData(String userQuery) {
        String searchPrompt = String.format("""
                다음 항목을 웹 검색하여 실제 데이터를 수집하고 정리하세요.
                서비스 분야: %s
                
                1. 관련 시장 규모 2024 (수치 + 출처)
                2. 연간 성장률 CAGR 2024 (수치 + 출처)
                3. 주요 경쟁사 현황 및 매출 (실제 서비스명 + 수치 + 출처)
                4. 사용자 불편사항 설문 데이터 (퍼센트 수치 + 출처)
                5. 관련 법규 최신 조항 (법 조항 번호 + 내용)
                """, userQuery);

        Map<String, Object> requestBody = Map.of(
                "model", "gpt-4o",
                "tools", List.of(Map.of("type", "web_search_preview")),
                "max_output_tokens", 3000,
                "input", List.of(Map.of("role", "user", "content", searchPrompt))
        );

        try {
            String response = restClientBuilder.build()
                    .post()
                    .uri("https://api.openai.com/v1/responses")
                    .header("Authorization", "Bearer " + openAiApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            // 텍스트 블록 추출
            JsonNode root = objectMapper.readTree(response);
            StringBuilder result = new StringBuilder();
            for (JsonNode item : root.path("output")) {
                if ("message".equals(item.path("type").asText())) {
                    for (JsonNode block : item.path("content")) {
                        if ("output_text".equals(block.path("type").asText())) {
                            result.append(block.path("text").asText());
                        }
                    }
                }
            }
            return result.toString();

        } catch (Exception e) {
            log.warn("웹 검색 실패, 빈 값으로 진행: {}", e.getMessage());
            return "웹 검색 데이터 없음";
        }
    }

    /**
     * STEP 2A — PRD 앞부분 생성 (overview ~ releaseSchedule)
     */
    private String generatePartA(String userQuery, List<String> featureList, String marketData) {
        String featureStr = "- " + String.join("\n- ", featureList);

        String prompt = String.format("""
                수집된 시장 데이터:
                === 시장 데이터 ===
                %s
                =================
                
                아래 JSON 형식으로 PRD 앞부분을 작성하세요.
                
                필수 조건:
                - background: 실제 수치 4개 이상, 출처 명시 (배열이 아닌 문자열로 작성)
                - kpi: 최소 7개, basis에 외부 기관 출처 명시
                - competitors: 실제 서비스명 5개, 실제 매출액 + 출처
                - userPainPoints: 최소 5개, 서로 다른 출처, 퍼센트 수치
                - coreFeatures: 기능 목록 10개 개별 항목, 요구사항 4개씩
                - userPersonas: 3개, 구체적 정보
                - competitiveMatrix: 경쟁사 5개 vs 기준 5개
                - techStack: 8개 레이어
                - releaseSchedule: 최소 6개 마일스톤
                
                JSON:
                {
                  "projectOverview": "한 줄 개요",
                  "background": "실제 수치 4개 이상 포함 (출처 명시) — 문자열로 작성",
                  "goals": ["목표1","목표2","목표3","목표4"],
                  "kpi": [{"metric":"지표","target":"수치","basis":"출처: 기관명 (YYYY년)","measurementMethod":"측정방법","frequency":"주기"}],
                  "marketData": {
                    "marketSize": "시장규모 + 출처",
                    "growthRate": "성장률 + 출처",
                    "competitors": [{"name":"실제서비스명","revenue":"매출+출처","marketShare":"점유율","feature":"강점","weakness":"약점","differentiator":"차별화"}],
                    "userPainPoints": [{"point":"Pain Point","data":"XX%% 불편 (출처: 기관명, YYYY년)","impact":"영향도"}]
                  },
                  "userPersonas": [{"name":"이름","age":"나이","job":"직업","techLevel":"낮음/중간/높음","goal":"목표","painPoint":"불편함","usagePattern":"이용패턴"}],
                  "competitiveMatrix": {
                    "criteria": ["기준1","기준2","기준3","기준4","기준5"],
                    "competitors": [{"name":"서비스명","scores":["상/중/하","상/중/하","상/중/하","상/중/하","상/중/하"]}]
                  },
                  "coreFeatures": [{"name":"기능명","description":"설명","priority":"P0/P1/P2","requirements":["요구사항1","요구사항2","요구사항3","요구사항4"]}],
                  "mvpScope": {"included":["기능1","기능2","기능3","기능4"],"excluded":["기능1","기능2"],"rationale":"근거"},
                  "userFlow": ["1. 단계1 — 설명","2. 단계2 — 설명","3. 단계3 — 설명","4. 단계4 — 설명","5. 단계5 — 설명","6. 단계6 — 설명"],
                  "uxConsiderations": [{"category":"카테고리","detail":"상세 내용","reference":"참고 기준"}],
                  "techStack": {"backend":"기술 - 이유","frontend":"기술 - 이유","database":"기술 - 이유","cache":"기술 - 이유","messageQueue":"기술 - 이유","cdn":"기술 - 이유","monitoring":"기술 - 이유","auth":"기술 - 이유"},
                  "nonFunctionalRequirements": {"performance":"수치+출처","security":"구체적 보안 기술","legal":"법 조항 번호 명시","scalability":"확장 전략","availability":"SLA+근거"},
                  "releaseSchedule": [{"date":"기간","milestone":"마일스톤명","description":"설명","team":"담당팀","deliverables":["산출물1","산출물2"]}]
                }
                
                사용자 요구사항: %s
                기능 목록: %s
                """, marketData, userQuery, featureStr);

        return callChatCompletions(prompt);
    }

    /**
     * STEP 2B — PRD 뒷부분 생성 (FSD, 운영정책, 리스크, 성공지표)
     */
    private String generatePartB(String userQuery, List<String> featureList, String marketData) {
        String featureStr = "- " + String.join("\n- ", featureList);

        String prompt = String.format("""
                수집된 시장 데이터:
                === 시장 데이터 ===
                %s
                =================
                
                아래 JSON 형식으로 PRD 뒷부분을 작성하세요.
                
                필수 조건:
                - fsd: 최소 12개, acceptanceCriteria 포함
                - operationPolicy: 최소 8개, 항목별 서로 다른 legalBasis
                - risks: 최소 5개, probability/impact/contingency 모두 포함
                - successMetrics: 최소 7개, measurementTool/frequency/owner 포함
                
                JSON:
                {
                  "risks": [{"title":"리스크명","probability":"상/중/하","impact":"상/중/하","description":"설명","strategy":"대응전략","contingency":"비상계획"}],
                  "fsd": [{"id":"FSD_001","category":"분류","description":"요구사항","action":"구현액션","acceptanceCriteria":"완료기준","note":"비고"}],
                  "operationPolicy": [{"id":"POL_001","category":"정책명","description":"정책설명","action":"처리액션","legalBasis":"법적근거 조항번호","note":"비고"}],
                  "successMetrics": [{"metric":"지표명","target":"목표치","measurementTool":"측정도구","frequency":"측정주기","owner":"담당팀"}]
                }
                
                서비스 요구사항: %s
                기능 목록: %s
                """, marketData, userQuery, featureStr);

        return callChatCompletions(prompt);
    }

    /**
     * chat.completions API 호출 (response_format JSON 강제)
     */
    private String callChatCompletions(String userPrompt) {
        Map<String, Object> requestBody = Map.of(
                "model", "gpt-4o",
                "temperature", 0.1,
                "max_tokens", 8192,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        try {
            String response = restClientBuilder.build()
                    .post()
                    .uri("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer " + openAiApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            return root.path("choices").get(0).path("message").path("content").asText();

        } catch (Exception e) {
            log.error("Chat Completions 호출 실패: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Part A + Part B JSON 합치기
     */
    private String mergeJsonParts(String partA, String partB) {
        try {
            JsonNode nodeA = objectMapper.readTree(partA);
            JsonNode nodeB = objectMapper.readTree(partB);

            com.fasterxml.jackson.databind.node.ObjectNode merged =
                    (com.fasterxml.jackson.databind.node.ObjectNode) nodeA;

            nodeB.fields().forEachRemaining(entry ->
                    merged.set(entry.getKey(), entry.getValue()));

            return objectMapper.writeValueAsString(merged);

        } catch (Exception e) {
            log.error("JSON 합치기 실패: {}", e.getMessage());
            return partA;
        }
    }
}