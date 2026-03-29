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
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Search 에이전트 — 웹 검색으로 시장 데이터 수집
 *
 * 역할:
 *   사용자 요구사항을 분석하여 관련 시장 데이터를 웹에서 수집합니다.
 *   수집된 데이터는 PrdAgent의 PRD 작성 근거로 활용됩니다.
 *
 * 수집 항목:
 *   1. 국내 시장 규모 및 성장률
 *   2. 주요 경쟁사 현황 및 매출
 *   3. 사용자 Pain Point 설문 데이터
 *   4. 관련 법규 최신 조항
 *   5. 기술 트렌드 및 성능 벤치마크
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchAgent {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.api-key}")
    private String openAiApiKey;

    public PipelineState execute(PipelineState state) {
        log.info("Search 에이전트 시작 — 시장 데이터 수집");

        try {
            String domain = extractDomain(state.getUserQuery());
            String marketData = collectAllMarketData(state.getUserQuery(), domain);

            log.info("Search 에이전트 완료 — {}자 수집", marketData.length());

            return state.toBuilder()
                    .marketResearch(marketData)
                    .statusMessage("Search 에이전트 완료 — 시장 데이터 수집")
                    .build();

        } catch (Exception e) {
            log.error("Search 에이전트 실패: {}", e.getMessage());
            return state.toBuilder()
                    .marketResearch("웹 검색 실패: " + e.getMessage())
                    .statusMessage("Search 에이전트 실패")
                    .build();
        }
    }

    /**
     * 5개 분야 순차 검색
     */
    private String collectAllMarketData(String userQuery, String domain) {
        List<String> queries = List.of(
                // 1. 시장 규모 / 경쟁사
                String.format("""
            다음을 웹 검색하여 한국 시장 데이터를 수집하고 수치+출처를 정리하세요:
            1. "국내 %s 시장 규모 2024 조원" 검색
            2. "한국 %s 연간 성장률 CAGR 2024" 검색
            3. "쿠팡 네이버 11번가 G마켓 %s 시장점유율 매출 2023 2024" 검색
            
            형식:
            [시장규모] 수치 + 출처(기관명, 날짜)
            [성장률] 수치 + 출처
            [경쟁사매출] 각 사별 실제 매출액 + 출처
            반드시 한국 서비스명만 사용. Shopify, Magento 등 글로벌 플랫폼 금지.
            """, domain, domain, domain),

                // 2. Pain Point
                String.format("""
            다음을 웹 검색하여 한국 사용자 불편 데이터를 수집하세요:
            1. "%s 사용자 불편사항 불만족 설문 통계 2024" 검색
            2. "온라인쇼핑 소비자 불만 한국소비자원 KCA 2024" 검색
            3. "%s 회원가입 로그인 UX 이탈률 문제점 리서치" 검색
            4. "모바일 %s 사용자 이탈 원인 통계 2024" 검색
            
            형식:
            [Pain Point 1] 내용 + 퍼센트 수치 + 출처
            [Pain Point 2] 내용 + 퍼센트 수치 + 출처
            [Pain Point 3] 내용 + 퍼센트 수치 + 출처
            [Pain Point 4] 내용 + 퍼센트 수치 + 출처
            [Pain Point 5] 내용 + 퍼센트 수치 + 출처
            반드시 한국 기관 출처만 사용.
            """, domain, domain, domain),

                // 3. 법규
                """
                    다음을 웹 검색하여 최신 법규 정보를 수집하세요:
                    1. "개인정보보호법 2024 최신 개정 조항 이커머스 전자상거래" 검색
                    2. "전자상거래법 소비자보호법 2024 개정 주요 내용" 검색
                    3. "전자금융거래법 PG결제 관련 규정 2024" 검색
                    
                    형식:
                    [개인정보보호법] 조항 번호 + 핵심 내용
                    [전자상거래법] 조항 번호 + 핵심 내용
                    [전자금융거래법] 조항 번호 + 핵심 내용
                    """,

                // 4. 기술 트렌드
                String.format("""
            다음을 웹 검색하여 기술/성능 데이터를 수집하세요:
            1. "%s 백엔드 기술 스택 트렌드 2024 Spring Django" 검색
            2. "%s API 응답속도 성능 벤치마크 업계 평균 2024" 검색
            3. "%s 동시접속 서버 스케일링 사례 2024" 검색
            
            형식:
            [권장기술스택] 각 레이어별 권장 기술 + 선택 이유
            [성능벤치마크] 업계 평균 응답속도, 동시접속 수치 + 출처
            [아키텍처] 업계 표준 아키텍처 패턴
            """, domain, domain, domain),

                // 5. 사용자 통계
                String.format("""
            다음을 웹 검색하여 한국 사용자/비즈니스 데이터를 수집하세요:
            1. "한국 %s 연령별 이용률 통계 2024" 검색
            2. "%s 구매 전환율 업계 평균 벤치마크 한국 2024" 검색
            3. "%s 재구매율 고객 유지율 통계 한국 2024" 검색
            4. "모바일 %s 비율 PC 대비 2024" 검색
            
            형식:
            [사용자통계] 연령별 이용률 + 출처
            [전환율] 업계 평균 수치 + 출처
            [재구매율] 수치 + 출처
            [모바일비중] 수치 + 출처
            """, domain, domain, domain, domain)
        );

        List<String> labels = List.of("시장규모/경쟁사", "Pain Point", "법규", "기술트렌드", "사용자통계");

        // 5개 병렬 실행
        List<CompletableFuture<String>> futures = IntStream.range(0, queries.size())
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    log.info("  웹 검색 {}/5: {}...", i + 1, labels.get(i));
                    String result = searchWeb(queries.get(i));
                    return "=== " + labels.get(i) + " ===\n" + result;
                }))
                .toList();

        // 전체 완료 대기 후 합치기
        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * OpenAI responses API + web_search_preview 호출
     */
    private String searchWeb(String query) {
        Map<String, Object> requestBody = Map.of(
                "model", "gpt-4o",
                "tools", List.of(Map.of("type", "web_search_preview")),
                "max_output_tokens", 2000,
                "input", List.of(Map.of("role", "user", "content", query))
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
            log.warn("웹 검색 실패: {}", e.getMessage());
            return "검색 실패";
        }
    }

    /**
     * 사용자 쿼리에서 서비스 도메인 추출
     */
    private String extractDomain(String userQuery) {
        Map<String, Object> requestBody = Map.of(
                "model", "gpt-4o-mini",
                "max_tokens", 50,
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content",
                                """
                                사용자의 서비스 설명을 읽고 해당 서비스의 업종/도메인을 한국어 2~4단어로만 답하세요.
                                예시) 이커머스 전자상거래, 음식 배달, 숙박 예약, 의료 헬스케어,
                                      부동산 중개, 방탈출 예약, 피트니스 헬스, 반려동물 케어
                                다른 설명 없이 도메인 단어만 출력하세요.
                                """),
                        Map.of("role", "user", "content", userQuery)
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
            String domain = root.path("choices").get(0)
                    .path("message").path("content").asText().trim();

            log.info("도메인 추출 결과: [{}]", domain);
            return domain;

        } catch (Exception e) {
            log.warn("도메인 추출 실패, userQuery 전체 사용: {}", e.getMessage());
            return userQuery;  // 실패 시 userQuery 자체를 검색어로 사용
        }
    }
}