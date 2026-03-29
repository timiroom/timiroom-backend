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

        ══ 작성 품질 원칙 ══
        - 모든 텍스트 필드는 최소 기준 글자수를 반드시 충족해야 합니다.
        - 단순 키워드 나열 금지 — 반드시 완성된 문장으로 작성하세요.
        - 숫자/수치가 포함된 모든 항목에는 반드시 출처(기관명 + 연도)를 괄호로 명시하세요.
        - 각 항목이 서로 다른 관점을 다루도록 중복 내용 금지.
        - "~을 제공합니다", "~을 지원합니다" 같은 단순 서술 금지 —
          반드시 "누가 / 무엇을 / 어떻게 / 왜" 구조로 구체적으로 작성하세요.

        ══ 절대 금지 ══
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



        String prompt = """
                수집된 시장 데이터:
                === 시장 데이터 ===
                {MARKET_DATA}
                =================
                {ROLLBACK}
                
                아래 JSON 형식으로 PRD 앞부분을 작성하세요.
                
                ══ 필드별 최소 기준 (반드시 충족) ══
                "projectOverview": "서비스 한 줄 개요 — 누구를 위해 무엇을 어떻게 제공하는가 (50자 이상)",
                
                ▸ background          : 500자 이상. 완성된 2~3문단. 시장 현황 → 문제점 → 기회 순서로 전개.
                                        실제 수치 4개 이상, 각 수치 옆에 (출처: 기관명, YYYY년) 표기 필수.
                ▸ goals               : 4개. 각 항목은 "~을 통해 ~을 달성하여 ~에 기여한다" 형식, 60자 이상.
                ▸ kpi                 : 7개 이상. target은 현재 기준값 → 목표값 형식 (예: "0% → 30%").
                                        basis는 반드시 "기관명, YYYY년 보고서" 형식으로 외부 출처 명시.
                ▸ competitors.feature : 각 강점은 2문장 이상. "무엇을 어떻게 제공하며, 그 결과 ~" 구조.
                ▸ competitors.weakness: 각 약점은 2문장 이상. 구체적 사용자 불편 상황 포함.
                ▸ userPainPoints.data : "XX% 사용자가 ~를 불편하다고 응답 (출처: 기관명, YYYY년, N=표본수)" 형식.
                ▸ userPainPoints.impact: 해당 페인 포인트가 서비스 선택/이탈에 미치는 구체적 영향 2문장 이상.
                ▸ coreFeatures.description: 각 기능은 100자 이상. "사용자가 ~상황에서 ~을 하면 시스템이 ~을 수행하여 ~효과를 낸다" 구조.
                ▸ coreFeatures.requirements: 4개씩. 각 항목은 "~할 때 → ~처리 → ~결과" 구조로 60자 이상.
                                             예) "이메일 인증" (X) → "회원가입 시 6자리 인증코드를 이메일 발송 → 10분 내 미입력 시 만료 처리 → 재발송 버튼 노출하여 UX 저하 방지" (O)
                ▸ userPersonas.goal  : 2문장 이상. 구체적 상황과 기대 결과 포함.
                ▸ userPersonas.painPoint: 2문장 이상. 현재 어떤 방식으로 해결하고 있는지 포함.
                ▸ userPersonas.usagePattern: 2문장 이상. 접속 빈도/시간대/디바이스 포함.
                ▸ userFlow            : 8단계 이상. 각 단계는 "사용자 행동 → 시스템 반응 → 다음 단계 연결" 구조, 80자 이상.
                                        예) "1. 서비스 최초 진입 — 비로그인 사용자가 메인 화면 접속 → 팝업 없이 주요 기능 미리보기 제공 → 하단 CTA 버튼으로 가입 유도"
                ▸ uxConsiderations.detail: 150자 이상. 구체적 구현 방법, 적용 화면/시나리오, 기대 효과 포함.
                ▸ techStack           : 8개 레이어 각각 선택 이유 2문장 이상 (기술 특성 + 이 서비스에 적합한 이유).
                ▸ nonFunctionalRequirements: 각 항목 구체적 수치 + 측정 방법 + 근거 출처 포함. 100자 이상.
                ▸ releaseSchedule.description: 각 마일스톤 100자 이상. 완료 기준(Done Criteria) 포함.
                ▸ releaseSchedule.deliverables: 3개 이상.
                ▸ mvpScope.rationale  : 100자 이상. 포함/제외 기준 논리와 비즈니스 근거 서술.
                
                검증 체크리스트 (생성 후 스스로 확인):
                [ ] background가 500자 이상인가?
                [ ] 경쟁사가 모두 한국 서비스인가?
                [ ] KPI basis에 "내부 목표" 표현이 없는가?
                [ ] coreFeatures.requirements 각 항목이 60자 이상인가?
                [ ] userFlow가 8단계 이상이며 각 단계가 80자 이상인가?
                [ ] techStack backend가 Spring Boot이며 이유가 2문장 이상인가?
                
                JSON:
                {
                  "projectOverview": "서비스 한 줄 개요 — 누구를 위해 무엇을 어떻게 제공하는가 (50자 이상)",
                  "background": "500자 이상 서술형 문단. 시장 현황 → 문제점 → 기회 순서. 수치마다 (출처: 기관명, YYYY년) 표기.",
                  "goals": ["~을 통해 ~을 달성하여 ~에 기여한다 (60자 이상)"],
                  "kpi": [{"metric":"지표명","target":"현재값 → 목표값","basis":"기관명, YYYY년 보고서","measurementMethod":"구체적 측정 방법","frequency":"측정 주기"}],
                  "marketData": {
                    "marketSize": "구체적 시장 규모 수치 + (출처: 기관명, YYYY년)",
                    "growthRate": "성장률 수치 + (출처: 기관명, YYYY년)",
                    "competitors": [{"name":"한국 실제 서비스명","revenue":"매출액 + (출처: 기관명, YYYY년)","marketShare":"점유율 + 출처","feature":"강점 2문장 이상 — 무엇을 어떻게 제공하며 결과는","weakness":"약점 2문장 이상 — 구체적 사용자 불편 포함","differentiator":"우리 서비스와의 차별화 포인트 2문장"}],
                    "userPainPoints": [{"point":"Pain Point 명칭","data":"XX% 사용자가 ~불편 응답 (출처: 기관명, YYYY년, N=표본수)","impact":"서비스 선택/이탈에 미치는 영향 2문장 이상"}]
                  },
                  "userPersonas": [{"name":"실제 느낌의 이름","age":"나이","job":"직업 + 회사 규모","techLevel":"낮음/중간/높음","goal":"구체적 상황과 기대 결과 포함 2문장","painPoint":"현재 해결 방식 포함 2문장","usagePattern":"접속 빈도/시간대/디바이스 포함 2문장"}],
                  "competitiveMatrix": {
                    "criteria": ["평가 기준 5개 — 단순 키워드 아닌 '~의 편의성' 형식"],
                    "competitors": [{"name":"서비스명","scores":["상/중/하","상/중/하","상/중/하","상/중/하","상/중/하"]}]
                  },
                  "coreFeatures": [{"name":"기능명","description":"사용자가 ~상황에서 ~하면 시스템이 ~하여 ~효과. 100자 이상.","priority":"P0/P1/P2","requirements":["~할 때 → ~처리 → ~결과 구조로 60자 이상"]}],
                  "mvpScope": {"included":["기능명"],"excluded":["기능명"],"rationale":"포함/제외 기준 논리와 비즈니스 근거 100자 이상"},
                  "userFlow": ["1. 단계명 — 사용자 행동 → 시스템 반응 → 다음 단계 연결 (80자 이상)"],
                  "uxConsiderations": [{"category":"카테고리명","detail":"구체적 구현 방법, 적용 화면/시나리오, 기대 효과 포함 150자 이상","reference":"UX 원칙 또는 참고 기준"}],
                  "techStack": {"backend":"Spring Boot 선택 이유 2문장 이상 — 기술 특성 + 이 서비스에 적합한 이유","frontend":"기술명 + 이유 2문장","database":"PostgreSQL + 이유 2문장","cache":"Redis + 이유 2문장","messageQueue":"기술명 + 이유 2문장","cdn":"기술명 + 이유 2문장","monitoring":"기술명 + 이유 2문장","auth":"OAuth 2.0 + 이유 2문장"},
                  "nonFunctionalRequirements": {"performance":"구체적 수치 + 측정 방법 + 근거 출처, 100자 이상","security":"적용 보안 기술 + 대상 위협 + 규정 조항, 100자 이상","legal":"법 조항 번호 + 핵심 내용 + 위반 시 리스크, 100자 이상","scalability":"수평/수직 확장 전략 + 트리거 조건, 100자 이상","availability":"SLA 수치 + 달성 방법 + 장애 허용 시간, 100자 이상"},
                  "releaseSchedule": [{"date":"기간","milestone":"마일스톤명","description":"완료 기준(Done Criteria) 포함 100자 이상","team":"담당팀","deliverables":["산출물1","산출물2","산출물3"]}]
                }
                
                사용자 요구사항: {USER_QUERY}
                기능 목록: {FEATURE_STR}
                """
                .replace("{MARKET_DATA}", marketData)
                .replace("{ROLLBACK}", rollbackSection)
                .replace("{USER_QUERY}", state.getUserQuery())
                .replace("{FEATURE_STR}", featureStr);


        return callChatCompletions(prompt);
    }

    private String generatePartB(PipelineState state, String marketData, boolean isRollback) {
        String featureStr = "- " + String.join("\n- ", state.getFeatureList());
        String rollbackSection = isRollback ? buildRollbackSection(state) : "";



        String prompt = """
                수집된 시장 데이터:
                === 시장 데이터 ===
                {MARKET_DATA}
                =================
                {ROLLBACK}
                아래 JSON 형식으로 PRD 뒷부분을 작성하세요.

                ══ 필드별 최소 기준 (반드시 충족) ══
                ▸ fsd                 : 12개 이상.
                  - description       : "사용자/관리자가 ~상황에서 ~할 수 있어야 한다" 형식. 80자 이상.
                  - action            : 기술 스택 기반으로 구현 단계 3단계 이상. "①~② ~③~" 구조.
                                        예) "① Spring Security로 로그인 시도 횟수 Redis에 카운팅 → ② 3회 초과 시 계정 잠금 플래그 DB 저장 → ③ 5분 타이머 만료 후 Redis 키 삭제로 자동 해제"
                  - acceptanceCriteria: 테스트 가능한 조건 2개 이상. "~하면 ~이어야 한다" 형식.

                ▸ operationPolicy     : 8개 이상.
                  - description       : 정책의 목적(왜 필요한가)과 적용 범위(어디에 적용되는가) 2문장. 100자 이상.
                  - action            : 처리 프로세스를 순서대로 4단계 이상. "1) ~ → 2) ~ → 3) ~ → 4) ~" 구조.
                  - legalBasis        : "법령명 제X조(조항명) — 핵심 의무 내용" 형식. 항목마다 서로 다른 조항 사용.

                ▸ risks               : 5개 이상.
                  - description       : 리스크 발생 트리거 + 예상 피해 범위 + 사업에 미치는 영향 3문장. 150자 이상.
                  - strategy          : 구체적 예방 기술/프로세스 2가지 이상 명시. 100자 이상.
                  - contingency       : 발생 후 대응 절차 4단계 이상.
                                        예) "1단계: 즉시 서비스 일시 중단 및 장애 공지 → 2단계: 로그 분석으로 영향 범위 파악 → 3단계: 영향 사용자 개별 이메일/SMS 통보 → 4단계: 원인 제거 후 단계적 복구 → 5단계: 재발 방지 보고서 작성"

                ▸ successMetrics      : 7개 이상.
                  - target            : 현재 기준값 → 목표값 형식 (예: "0명 → 5,000명/월").
                  - measurementTool   : 구체적 도구명 (예: "Google Analytics 4 + Mixpanel").
                  - baselineNote      : 현재 수치가 없는 경우 "런칭 후 1개월 기준 수립" 등 명시.

                검증 체크리스트:
                [ ] fsd가 12개 이상이며 action이 3단계 이상인가?
                [ ] operationPolicy의 legalBasis가 전부 다른 조항인가?
                [ ] risks의 description이 150자 이상이며 contingency가 4단계 이상인가?
                [ ] successMetrics가 7개 이상이며 target에 현재값→목표값 형식인가?

                JSON:
                {
                  "risks": [{"title":"리스크명","probability":"상/중/하","impact":"상/중/하","description":"발생 트리거 + 피해 범위 + 사업 영향 3문장 150자 이상","strategy":"예방 기술/프로세스 2가지 이상 100자 이상","contingency":"1단계: ~ → 2단계: ~ → 3단계: ~ → 4단계: ~ (4단계 이상)"}],
                  "fsd": [{"id":"FSD_001","category":"기능 분류","description":"사용자/관리자가 ~상황에서 ~할 수 있어야 한다 (80자 이상)","action":"① ~ → ② ~ → ③ ~ (3단계 이상, 기술 스택 기반)","acceptanceCriteria":"~하면 ~이어야 한다 조건 2개 이상","note":"비고 또는 연관 FSD"}],
                  "operationPolicy": [{"id":"POL_001","category":"정책 분류","description":"정책 목적 + 적용 범위 2문장 100자 이상","action":"1) ~ → 2) ~ → 3) ~ → 4) ~ (4단계 이상)","legalBasis":"법령명 제X조(조항명) — 핵심 의무 내용","note":"비고"}],
                  "successMetrics": [{"metric":"지표명","target":"현재값 → 목표값","measurementTool":"구체적 도구명","frequency":"측정 주기","owner":"담당팀","baselineNote":"기준값 수립 방법 또는 현재 상태"}]
                }

                서비스 요구사항: {USER_QUERY}
                기능 목록: {FEATURE_STR}
                """
                .replace("{MARKET_DATA}", marketData)
                .replace("{ROLLBACK}", rollbackSection)
                .replace("{USER_QUERY}", state.getUserQuery())
                .replace("{FEATURE_STR}", featureStr);

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
                "max_tokens", 16000,
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