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
 * PRD 에이전트 — 시장 데이터 기반 PRD 생성
 *
 * 역할:
 *   SearchAgent가 수집한 시장 데이터 + PmAgent의 기능 목록을 바탕으로
 *   구체적인 목표와 체크리스트가 포함된 PRD를 2단계로 생성합니다.
 *
 * rollback 지원:
 *   DBA/API 에이전트의 피드백이 있으면 해당 피드백을 반영하여 PRD를 재생성합니다.
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

        절대 금지:
        - 출처 없는 수치 사용
        - "내부 목표", "내부 설문조사" 표현
        - A사, B사 등 가상 서비스명
        - "약", "대략" 등 모호한 표현 (실제 추정치 제외)
        - 동일 기관을 3개 이상 연속 사용
        - background는 반드시 문자열로 작성 (배열 금지)
        - Shopify, Magento, WooCommerce 등 글로벌 플랫폼 (반드시 한국 서비스로 작성)
        - releaseSchedule은 정확히 6개 이상 작성
        - fsd는 정확히 12개 이상 작성
        - operationPolicy의 legalBasis는 항목별로 서로 다른 조항 사용 (동일 조항 반복 금지)

        반드시 JSON만 출력하고 다른 텍스트는 절대 포함하지 마세요.
        """;

    public PipelineState execute(PipelineState state) {
        log.info("PRD 에이전트 시작");

        // DBA/API 피드백이 있으면 rollback 모드
        boolean isRollback = hasRollbackFeedback(state);
        if (isRollback) {
            log.warn("PRD rollback 모드 — DBA/API 피드백 반영하여 재생성 ({}회째)",
                    state.getRollbackCount());
        }

        try {
            String marketData = state.getMarketResearch() != null
                    ? state.getMarketResearch() : "시장 데이터 없음";

            // STEP 2A — PRD 앞부분 생성
            log.info("STEP 2A: PRD 앞부분 생성 중...");
            String partA = generatePartA(state, marketData, isRollback);

            // STEP 2B — PRD 뒷부분 생성
            log.info("STEP 2B: PRD 뒷부분 생성 중...");
            String partB = generatePartB(state, marketData, isRollback);

            String prdDocument = mergeJsonParts(partA, partB);
            log.info("PRD 에이전트 완료 — {}chars", prdDocument.length());

            return state.toBuilder()
                    .prdDocument(prdDocument)
                    .prdFeedbackFromDba("")   // 피드백 초기화
                    .prdFeedbackFromApi("")
                    .statusMessage("PRD 에이전트 완료")
                    .build();

        } catch (Exception e) {
            log.error("PRD 에이전트 실패: {}", e.getMessage());
            return state.toBuilder()
                    .prdDocument("{}")
                    .statusMessage("PRD 에이전트 실패: " + e.getMessage())
                    .build();
        }
    }

    private boolean hasRollbackFeedback(PipelineState state) {
        return (state.getPrdFeedbackFromDba() != null && !state.getPrdFeedbackFromDba().isBlank())
                || (state.getPrdFeedbackFromApi() != null && !state.getPrdFeedbackFromApi().isBlank());
    }

    private String generatePartA(PipelineState state, String marketData, boolean isRollback) {
        String featureStr = "- " + String.join("\n- ", state.getFeatureList());
        String rollbackSection = isRollback ? buildRollbackSection(state) : "";

        String prompt = String.format("""
                수집된 시장 데이터:
                === 시장 데이터 ===
                %s
                =================
                %s

                아래 JSON 형식으로 PRD 앞부분을 작성하세요.

                ⚠️ 구체적 목표 (반드시 달성):
                - background: 한국 시장 실제 수치 4개 이상 포함, 각 수치에 출처 명시
                - kpi: 최소 7개, basis에 외부 기관 출처 명시 (내부 목표 금지)
                - competitors: 한국 실제 서비스명 5개, 실제 매출액 + 출처 포함
                - userPainPoints: 최소 5개, 서로 다른 출처, 퍼센트 수치
                - coreFeatures: 기능 목록 전체 개별 항목, 요구사항 4개씩
                - userPersonas: 3개, 구체적 정보
                - competitiveMatrix: 경쟁사 5개 vs 기준 5개
                - techStack: 8개 레이어 (Spring Boot 기반으로 작성)
                - releaseSchedule: 최소 6개 마일스톤

                검증 체크리스트 (생성 후 스스로 확인):
                [ ] 경쟁사가 모두 한국 서비스인가?
                [ ] background에 실제 수치 4개 이상인가?
                [ ] KPI basis에 "내부 목표" 표현이 없는가?
                [ ] techStack에 backend가 Spring Boot로 되어있는가?

                JSON:
                {
                 background: 한국 시장 실제 수치 4개 이상 포함, 각 수치에 출처 명시.
                            반드시 하나의 자연스러운 문단으로 작성. 시장 현황 → 문제점 → 기회 순서로 서술.
                            최소 200자 이상 작성.
                        
                         - coreFeatures: 기능 목록 전체 개별 항목. 요구사항은 4개씩이며
                           각 요구사항은 "무엇을 → 어떻게 → 왜" 구조로 구체적으로 작성.
                           예) "이메일 인증" (X) → "회원가입 시 이메일 인증 링크 발송 — 미인증 계정은 로그인 차단하여 스팸 계정 방지" (O)
                        
                         - userFlow: 최소 6단계. 각 단계는 사용자 행동 + 시스템 반응 + 다음 단계로 이어지는 흐름을 포함.
                           예) "1. 회원가입 — 사용자가 이메일/비밀번호 입력 → 시스템이 이메일 인증 링크 발송 → 인증 완료 시 자동 로그인"
                        
                         - uxConsiderations: 최소 6개. 각 항목의 detail은 2~3문장으로 구체적 구현 방법까지 명시.
                           단순 키워드 나열 금지.
                  "goals": ["목표1","목표2","목표3","목표4"],
                  "kpi": [{"metric":"지표","target":"수치","basis":"출처: 기관명 (YYYY년)","measurementMethod":"측정방법","frequency":"주기"}],
                  "marketData": {
                    "marketSize": "시장규모 + 출처",
                    "growthRate": "성장률 + 출처",
                    "competitors": [{"name":"한국서비스명","revenue":"매출+출처","marketShare":"점유율","feature":"강점","weakness":"약점","differentiator":"차별화"}],
                    "userPainPoints": [{"point":"Pain Point","data":"XX%% 불편 (출처: 한국기관명, YYYY년)","impact":"영향도"}]
                  },
                  "userPersonas": [{"name":"이름","age":"나이","job":"직업","techLevel":"낮음/중간/높음","goal":"목표","painPoint":"불편함","usagePattern":"이용패턴"}],
                  "competitiveMatrix": {
                    "criteria": ["기준1","기준2","기준3","기준4","기준5"],
                    "competitors": [{"name":"서비스명","scores":["상/중/하","상/중/하","상/중/하","상/중/하","상/중/하"]}]
                  },
                  "coreFeatures": [{"name":"기능명","description":"설명","priority":"P0/P1/P2","requirements":["요구사항1","요구사항2","요구사항3","요구사항4"]}],
                  "mvpScope": {"included":["기능1","기능2","기능3","기능4"],"excluded":["기능1","기능2"],"rationale":"근거"},
                  "userFlow": ["1. 단계 — 설명","2. 단계 — 설명","3. 단계 — 설명","4. 단계 — 설명","5. 단계 — 설명","6. 단계 — 설명"],
                  "uxConsiderations": [{"category":"카테고리","detail":"상세 내용","reference":"참고 기준"}],
                  "techStack": {"backend":"Spring Boot - 이유","frontend":"기술 - 이유","database":"PostgreSQL - 이유","cache":"Redis - 이유","messageQueue":"Kafka - 이유","cdn":"기술 - 이유","monitoring":"기술 - 이유","auth":"OAuth 2.0 - 이유"},
                  "nonFunctionalRequirements": {"performance":"수치+출처","security":"구체적 보안 기술","legal":"법 조항 번호 명시","scalability":"확장 전략","availability":"SLA+근거"},
                  "releaseSchedule": [{"date":"기간","milestone":"마일스톤명","description":"설명","team":"담당팀","deliverables":["산출물1","산출물2"]}]
                }

                사용자 요구사항: %s
                기능 목록: %s
                """,
                marketData,
                rollbackSection,
                state.getUserQuery(),
                featureStr
        );

        return callChatCompletions(prompt);
    }

    private String generatePartB(PipelineState state, String marketData, boolean isRollback) {
        String featureStr = "- " + String.join("\n- ", state.getFeatureList());
        String rollbackSection = isRollback ? buildRollbackSection(state) : "";

        String prompt = String.format("""
                수집된 시장 데이터:
                === 시장 데이터 ===
                %s
                =================
                %s

                아래 JSON 형식으로 PRD 뒷부분을 작성하세요.

                ⚠️ 구체적 목표 (반드시 달성):
                        - fsd: 최소 12개. description은 "사용자/관리자가 ~할 수 있어야 한다" 형식으로 구체적으로.
                          action은 구현 방법을 기술 스택 기준으로 작성.
                          acceptanceCriteria는 테스트 가능한 조건으로 작성.
                          예) "로그인 3회 실패 시 계정 잠금 → 5분 후 자동 해제, 잠금 중 로그인 시도 시 에러 메시지 표시"
                        
                        - operationPolicy: 최소 8개. description은 정책의 목적과 범위를 2문장으로.
                          action은 구체적인 처리 프로세스를 단계별로 작성.
                          legalBasis는 조항 번호와 핵심 내용 함께 명시.
                        
                        - risks: 최소 5개. description은 리스크 발생 시나리오를 구체적으로 서술 (최소 2문장).
                          strategy는 예방 조치를 구체적 기술/프로세스로 명시.
                          contingency는 발생 후 대응 절차를 단계별로 작성.
                          예) "1단계: 즉시 서비스 중단 → 2단계: 영향 범위 파악 → 3단계: 고객 통보 → 4단계: 복구"
                - successMetrics: 최소 7개, measurementTool/frequency/owner 포함

                검증 체크리스트:
                [ ] fsd가 12개 이상인가?
                [ ] operationPolicy의 legalBasis가 전부 다른 조항인가?
                [ ] risks에 contingency가 모두 있는가?
                [ ] successMetrics가 7개 이상인가?

                JSON:
                {
                  "risks": [{"title":"리스크명","probability":"상/중/하","impact":"상/중/하","description":"설명","strategy":"대응전략","contingency":"비상계획"}],
                  "fsd": [{"id":"FSD_001","category":"분류","description":"요구사항","action":"구현액션","acceptanceCriteria":"완료기준","note":"비고"}],
                  "operationPolicy": [{"id":"POL_001","category":"정책명","description":"정책설명","action":"처리액션","legalBasis":"법적근거 조항번호","note":"비고"}],
                  "successMetrics": [{"metric":"지표명","target":"목표치","measurementTool":"측정도구","frequency":"측정주기","owner":"담당팀"}]
                }

                서비스 요구사항: %s
                기능 목록: %s
                """,
                marketData,
                rollbackSection,
                state.getUserQuery(),
                featureStr
        );

        return callChatCompletions(prompt);
    }

    private String buildRollbackSection(PipelineState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n⚠️ ROLLBACK 모드 — 아래 피드백을 반드시 반영하세요:\n");

        if (state.getPrdFeedbackFromDba() != null && !state.getPrdFeedbackFromDba().isBlank()) {
            sb.append("DBA 에이전트 피드백:\n").append(state.getPrdFeedbackFromDba()).append("\n");
        }
        if (state.getPrdFeedbackFromApi() != null && !state.getPrdFeedbackFromApi().isBlank()) {
            sb.append("API 에이전트 피드백:\n").append(state.getPrdFeedbackFromApi()).append("\n");
        }
        return sb.toString();
    }

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

    private String mergeJsonParts(String partA, String partB) {
        try {
            JsonNode nodeA = objectMapper.readTree(partA);
            JsonNode nodeB = objectMapper.readTree(partB);
            com.fasterxml.jackson.databind.node.ObjectNode merged =
                    (com.fasterxml.jackson.databind.node.ObjectNode) nodeA;
            nodeB.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
            return objectMapper.writeValueAsString(merged);
        } catch (Exception e) {
            log.error("JSON 합치기 실패: {}", e.getMessage());
            return partA;
        }
    }
}