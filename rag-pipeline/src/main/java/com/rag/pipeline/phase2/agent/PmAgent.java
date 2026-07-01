package com.rag.pipeline.phase2.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.pipeline.common.skills.PmSkillsLoader;
import com.rag.pipeline.phase2.state.PipelineState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PM 에이전트 — 기능 도출 및 하위 에이전트 지시사항 생성
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmAgent {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final PmSkillsLoader pmSkillsLoader;

    @Value("${spring.ai.openai.api-key}")
    private String foundryApiKey;

    private static final String FOUNDRY_URL =
            "https://align-it-resource.services.ai.azure.com/openai/v1/responses";

    private static final String SYSTEM_INSTRUCTIONS = """
            당신은 시니어 소프트웨어 아키텍트이자 PM입니다.
            아래 요구사항을 분석하여 JSON 형식으로만 응답하세요.

            ## 응답 형식 (필수 - 절대 변경 금지)
            응답은 반드시 다음의 JSON 형식만 출력하세요. 다른 텍스트는 절대 금지입니다.

            {
              "featureList": ["기능1", "기능2", "기능3", "기능4", "기능5"],
              "dbaInstruction": "DBA에게 전달할 DB 설계 지시사항",
              "apiInstruction": "API 개발자에게 전달할 API 설계 지시사항"
            }

            ## 필수 규칙
            1. 응답은 순수 JSON만 출력 - 마크다운, 설명, 다른 텍스트 절대 금지
            2. { 로 시작해서 } 로 끝나야 함
            3. featureList는 배열이고 반드시 3개 이상의 기능을 포함해야 함
            4. 각 기능은 문자열 (예: "사용자 인증", "프로필 관리", "결제 처리" 등)
            5. dbaInstruction과 apiInstruction은 문자열

            ## featureList 작성 가이드
            - 서비스를 구성하는 모든 핵심·부가 기능을 빠짐없이 열거
            - 개수 제한 없음, 최대한 상세하게 (최소 5개 이상)
            - 각 기능은 명확하고 구체적인 문구
            - 예시: "대학생 인증", "중고거래 등록", "실시간 채팅", "결제 처리", "상품 검색", "리뷰 시스템"

            ## dbaInstruction 작성
            - 필요한 테이블명 명시 (users, products, orders 등)
            - 주요 관계 및 제약조건 명시
            - 인덱스 전략

            ## apiInstruction 작성
            - 필요한 엔드포인트 명시 (예: POST /auth/login, GET /products 등)
            - 인증 방식 명시 (JWT, OAuth 등)
            - 주요 응답 형식

            ## 절대 준수
            JSON 외 다른 텍스트는 절대 포함하지 마세요.
            유효하지 않은 JSON을 반환하면 시스템이 작동하지 않습니다.
            """;

    // ── Collaborative API ───────────────────────────────────────

    /**
     * Round 1: Phase1 컨텍스트로부터 기능 목록 + DBA/API 지시사항 초안 작성.
     * 기존 execute()와 동일하게 동작하며 형식만 wrapping.
     */
    public PipelineState executeDraft(PipelineState state) {
        return execute(state);
    }

    /**
     * Round 2: DBA/API/PRD 초안을 검토하여 누락된 기능이 있으면 featureList에 추가.
     * 다른 에이전트들의 초안에서 PM이 놓친 기능을 발견하면 보완.
     */
    public PipelineState refine(PipelineState state) {
        log.info("PM 에이전트 기능 목록 보완 — DBA/API/PRD 초안 검토");

        String currentFeatureStr = (state.getFeatureList() != null && !state.getFeatureList().isEmpty())
                ? "- " + String.join("\n- ", state.getFeatureList())
                : "(기능 목록 없음)";

        String dbaSnippet = state.getDbSchema() != null
                ? state.getDbSchema().substring(0, Math.min(2000, state.getDbSchema().length()))
                : "(DBA 초안 없음)";

        String apiSnippet = state.getApiSpec() != null
                ? state.getApiSpec().substring(0, Math.min(2000, state.getApiSpec().length()))
                : "(API 초안 없음)";

        String prdSnippet = state.getPrdDocument() != null
                ? state.getPrdDocument().substring(0, Math.min(1000, state.getPrdDocument().length()))
                : "(PRD 초안 없음)";

        String userContent = """
                현재 기능 목록 (PM이 1차로 도출한 결과):
                %s

                DBA 에이전트 초안 DB 스키마:
                %s

                API 에이전트 초안 스펙:
                %s

                PRD 에이전트 초안 (앞부분):
                %s

                위 3개 에이전트 초안을 검토하여, 현재 기능 목록에서 누락된 기능이 있으면 추가하세요.
                - DBA 스키마의 새 테이블이 기능 목록에 없으면 해당 기능 추가
                - API 스펙의 새 엔드포인트 그룹이 기능 목록에 없으면 해당 기능 추가
                - PRD 요구사항에서 기능 목록에 없는 기능이 있으면 추가
                - 누락된 기능이 없으면 현재 기능 목록을 그대로 반환
                dbaInstruction과 apiInstruction도 업데이트된 기능 목록에 맞게 수정하세요.
                """.formatted(currentFeatureStr, dbaSnippet, apiSnippet, prdSnippet);

        Map<String, Object> payload = Map.of(
                "model", "gpt-5.4-mini",
                "instructions", SYSTEM_INSTRUCTIONS,
                "input", List.of(Map.of("role", "user", "content", userContent)),
                "max_output_tokens", 4000
        );

        try {
            String raw = restClientBuilder.build()
                    .post()
                    .uri(FOUNDRY_URL)
                    .header("Authorization", "Bearer " + foundryApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            String response = extractText(objectMapper.readTree(raw));
            PmResponse parsed = parseResponse(response);

            List<String> finalFeatureList = parsed.featureList();
            if (finalFeatureList.isEmpty() && state.getFeatureList() != null) {
                finalFeatureList = state.getFeatureList();
            }
            log.info("PM 에이전트 보완 완료 — {} 기능", finalFeatureList.size());

            return state.toBuilder()
                    .featureList(finalFeatureList)
                    .dbaInstruction(parsed.dbaInstruction().isBlank()
                            ? state.getDbaInstruction() : parsed.dbaInstruction())
                    .apiInstruction(parsed.apiInstruction().isBlank()
                            ? state.getApiInstruction() : parsed.apiInstruction())
                    .build();

        } catch (Exception e) {
            log.error("PM 보완 실패 — 기존 featureList 유지: {}", e.getMessage());
            return state;
        }
    }

    // ── 기존 Sequential API ──────────────────────────────────────

    public PipelineState execute(PipelineState state) {
        log.info("PM 에이전트 시작 — query: '{}'", state.getUserQuery());

        String skillsSection = pmSkillsLoader.hasSkills()
                ? pmSkillsLoader.findRelevantSkills(state.getContextPrompt(), 5)
                : "";

        if (!skillsSection.isBlank()) {
            log.info("PM 스킬 프롬프트 주입 완료 — {} 자 추가됨", skillsSection.length());
        } else {
            log.warn("PM 스킬 미적용 — 로드된 스킬 없음, 기본 프롬프트만 사용");
        }

        // 폼에서 추출된 기능 목록을 프롬프트에 명시하여 PM 에이전트가 빠뜨리지 않도록 힌트 제공
        List<String> formFeatures = state.getFeatureList();
        String formFeaturesHint = (formFeatures != null && !formFeatures.isEmpty())
                ? "\n\n[폼에서 입력된 기능 목록 — 반드시 포함하고 더 상세히 확장하세요]\n"
                + formFeatures.stream().map(f -> "- " + f).collect(Collectors.joining("\n"))
                : "";

        String userContent = skillsSection + "\n요구사항:\n" + state.getContextPrompt() + formFeaturesHint;

        Map<String, Object> payload = Map.of(
                "model", "gpt-5.4-mini",
                "instructions", SYSTEM_INSTRUCTIONS,
                "input", List.of(Map.of("role", "user", "content", userContent)),
                "max_output_tokens", 8000
        );

        try {
            // 🔴 API 요청
            log.info("📤 PM Agent API 요청 시작...");
            String raw = restClientBuilder.build()
                    .post()
                    .uri(FOUNDRY_URL)
                    .header("Authorization", "Bearer " + foundryApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            // 🔴 Raw 응답 콘솔 출력
            System.out.println("\n========== 📥 PM Agent Raw 응답 ==========");
            System.out.println(raw);
            System.out.println("==========================================\n");

            // 🔴 파싱된 구조 출력
            JsonNode responseTree = objectMapper.readTree(raw);
            System.out.println("\n========== 📋 PM Agent 파싱된 구조 ==========");
            System.out.println(responseTree.toPrettyString());
            System.out.println("=============================================\n");

            String response = extractText(responseTree);

            // 🔴 extractText 직후 즉시 System.err로 출력 (버퍼 무시)
            System.err.println("\n========== 🔍 extractText 직후 ==========");
            System.err.println("응답 길이: " + response.length());
            System.err.println("응답 처음 500자: ");
            System.err.println(response.substring(0, Math.min(500, response.length())));
            System.err.println("\n응답 마지막 500자: ");
            System.err.println(response.substring(Math.max(0, response.length() - 500)));
            System.err.println("\nJSON 시작: " + response.trim().startsWith("{"));
            System.err.println("JSON 끝: " + response.trim().endsWith("}"));
            System.err.println("==========================================\n");
            System.err.flush();  // 강제 플러시

            log.info("📄 추출된 텍스트 길이: {}", response.length());
            log.info("📄 JSON 시작: {}, 끝: {}", response.trim().startsWith("{"), response.trim().endsWith("}"));
            log.info("📄 응답 처음 500자: {}", response.substring(0, Math.min(500, response.length())));
            log.info("📄 응답 마지막 500자: {}", response.substring(Math.max(0, response.length() - 500)));

            PmResponse parsed = parseResponse(response);

            // 🔴 파싱된 응답 출력
            System.out.println("\n========== ✅ PM Agent 파싱된 응답 ==========");
            System.out.println("featureList 개수: " + parsed.featureList().size());
            System.out.println("featureList: " + parsed.featureList());
            System.out.println("dbaInstruction: " + (parsed.dbaInstruction().isEmpty() ? "(비어있음)" : parsed.dbaInstruction().substring(0, Math.min(100, parsed.dbaInstruction().length()))));
            System.out.println("apiInstruction: " + (parsed.apiInstruction().isEmpty() ? "(비어있음)" : parsed.apiInstruction().substring(0, Math.min(100, parsed.apiInstruction().length()))));
            System.out.println("==========================================\n");

            // PM 에이전트가 빈 featureList를 반환하면 폼 데이터로 폴백
            List<String> finalFeatureList = parsed.featureList();
            if (finalFeatureList.isEmpty() && formFeatures != null && !formFeatures.isEmpty()) {
                log.warn("⚠️ PM 에이전트 빈 featureList 반환 — 폼 데이터 featureList로 폴백 ({} 기능)", formFeatures.size());
                finalFeatureList = formFeatures;
            }

            log.info("✅ PM 에이전트 완료 — {} 기능 도출", finalFeatureList.size());

            return state.toBuilder()
                    .featureList(finalFeatureList)
                    .dbaInstruction(parsed.dbaInstruction())
                    .apiInstruction(parsed.apiInstruction())
                    .statusMessage("PM 에이전트 완료 — 기능 목록 도출")
                    .build();

        } catch (Exception e) {
            log.error("❌ PM 에이전트 실패: {}", e.getMessage(), e);
            // API 실패 시에도 폼 기능 목록은 보존
            List<String> fallback = (formFeatures != null && !formFeatures.isEmpty())
                    ? formFeatures : List.of();
            log.warn("⚠️ PM 에이전트 실패 — 폼 데이터 featureList로 폴백 ({} 기능)", fallback.size());
            return state.toBuilder()
                    .featureList(fallback)
                    .dbaInstruction("")
                    .apiInstruction("")
                    .statusMessage("PM 에이전트 실패 — 폼 기능 목록으로 진행")
                    .build();
        }
    }

    private PmResponse parseResponse(String response) {
        try {
            // 🔴 원본 응답 출력
            System.out.println("\n========== 🔍 PM Agent 응답 원본 ==========");
            System.out.println("길이: " + response.length());
            System.out.println(response);
            System.out.println("==========================================\n");

            String clean = response.trim()
                    .replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();

            // 🔴 정제된 JSON 출력
            System.out.println("\n========== 🧹 PM Agent 정제된 JSON ==========");
            System.out.println("길이: " + clean.length());
            System.out.println(clean);
            System.out.println("==========================================\n");

            JsonNode root = objectMapper.readTree(clean);

            // 🔴 featureList 파싱 전 확인
            System.out.println("\n========== 🔍 featureList 파싱 ==========");
            JsonNode featureListNode = root.path("featureList");
            System.out.println("featureList exists: " + root.has("featureList"));
            System.out.println("featureList isArray: " + featureListNode.isArray());
            System.out.println("featureList size: " + featureListNode.size());

            List<String> featureList = new ArrayList<>();
            featureListNode.forEach(n -> {
                String feature = n.asText();
                System.out.println("  - " + feature);
                featureList.add(feature);
            });
            System.out.println("==========================================\n");

            String dbaInstruction = root.path("dbaInstruction").asText("");
            String apiInstruction = root.path("apiInstruction").asText("");

            log.info("✅ featureList 파싱 완료: {} 개", featureList.size());

            return new PmResponse(featureList, dbaInstruction, apiInstruction);

        } catch (com.fasterxml.jackson.core.JsonParseException e) {
            log.error("❌ PM 에이전트 JSON 파싱 에러 — line: {}, column: {}",
                    e.getLocation().getLineNr(), e.getLocation().getColumnNr());
            log.error("❌ 에러 메시지: {}", e.getMessage());

            // 🔴 에러 발생한 응답 전체 출력
            System.out.println("\n========== ❌ PM Agent JSON 파싱 에러 ==========");
            System.out.println("에러: " + e.getMessage());
            System.out.println("라인: " + e.getLocation().getLineNr() + ", 칼럼: " + e.getLocation().getColumnNr());
            System.out.println("응답: " + response);
            System.out.println("==========================================\n");

            e.printStackTrace();
            return new PmResponse(List.of("JSON 파싱 실패"),
                    "JSON 파싱 실패: " + e.getMessage(),
                    "JSON 파싱 실패: " + e.getMessage());
        } catch (Exception e) {
            log.error("❌ PM 에이전트 응답 파싱 실패 — {}", e.getMessage(), e);

            // 🔴 에러 응답 출력
            System.out.println("\n========== ❌ PM Agent 파싱 실패 ==========");
            System.out.println("에러: " + e.getClass().getSimpleName());
            System.out.println("메시지: " + e.getMessage());
            System.out.println("응답: " + response);
            System.out.println("==========================================\n");

            e.printStackTrace();
            return new PmResponse(List.of("파싱 실패"),
                    "파싱 실패: " + e.getMessage(),
                    "파싱 실패: " + e.getMessage());
        }
    }

    private String extractText(JsonNode root) {
        log.debug("🔎 PM Agent 텍스트 추출 시작...");

        // 패턴 1: choices 배열
        if (root.has("choices") && root.get("choices").isArray()) {
            log.debug("  → 'choices' 배열 발견");
            JsonNode choices = root.path("choices");
            if (choices.size() > 0) {
                JsonNode choice = choices.get(0);
                if (choice.has("message") && choice.get("message").has("content")) {
                    String text = choice.path("message").path("content").asText("").trim();
                    if (!text.isEmpty()) {
                        log.debug("  ✓ choices 패턴에서 텍스트 추출 성공");
                        return text;
                    }
                }
            }
        }

        // 패턴 2: output 배열
        if (root.has("output") && root.get("output").isArray()) {
            log.debug("  → 'output' 배열 발견");
            for (JsonNode item : root.path("output")) {
                if ("message".equals(item.path("type").asText())) {
                    log.debug("    → message 타입 발견");
                    for (JsonNode block : item.path("content")) {
                        if ("output_text".equals(block.path("type").asText())) {
                            String text = block.path("text").asText("").trim();
                            if (!text.isEmpty()) {
                                log.debug("    ✓ output 패턴에서 텍스트 추출 성공");
                                return text;
                            }
                        }
                    }
                }
            }
        }

        // 패턴 3: content 필드
        if (root.has("content")) {
            String text = root.path("content").asText("").trim();
            if (!text.isEmpty()) {
                log.debug("  ✓ content 필드에서 텍스트 추출 성공");
                return text;
            }
        }

        log.warn("⚠️ PM Agent 텍스트 추출 실패 - 예상된 구조를 찾을 수 없음");
        return "{}";
    }

    record PmResponse(
            List<String> featureList,
            String dbaInstruction,
            String apiInstruction
    ) {}
}