package com.rag.pipeline.phase1.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * STEP 2 — Query Expansion
 *
 * 사용자의 모호한 자연어 요청을 검색에 최적화된
 * 기술 검색어 여러 개로 확장합니다.
 *
 * 예시:
 *   입력: "로그인 만들어줘"
 *   출력: ["로그인 만들어줘",        ← 원본 항상 포함
 *          "사용자 인증",
 *          "JWT 토큰 인증",
 *          "Spring Security 설정",
 *          "로그인 API 엔드포인트",
 *          "세션 관리"]
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryExpansionService {

    private final ChatClient.Builder chatClientBuilder;

    private static final String EXPANSION_PROMPT = """
            당신은 소프트웨어 개발 전문가입니다.
            사용자의 요청을 분석하여 RAG 시스템 검색에 최적화된 기술 검색어를 생성하세요.
            
            규칙:
            - 정확히 5개의 검색어를 생성하세요
            - 각 검색어는 줄바꿈으로 구분하세요
            - 검색어만 출력하고 번호, 설명 등 부가 텍스트는 절대 포함하지 마세요
            - 기술적이고 구체적인 용어를 사용하세요
            
            사용자 요청: %s
            """;

    /**
     * 사용자 쿼리를 5개의 기술 검색어로 확장
     * 원본 쿼리 포함 총 6개 반환
     *
     * @param userQuery 원본 사용자 입력
     * @return 확장된 검색어 목록
     */
    public List<String> expand(String userQuery) {
        log.debug("Query Expansion 시작 — 원본: '{}'", userQuery);

        // GPT-4o-mini 사용 (비용 최적화)
        ChatClient chatClient = chatClientBuilder
                .defaultOptions(
                        org.springframework.ai.openai.OpenAiChatOptions.builder()
                                .withModel("gpt-4o-mini")
                                .withTemperature(0.2)
                                .build()
                )
                .build();

        String response = chatClient
                .prompt(String.format(EXPANSION_PROMPT, userQuery))
                .call()
                .content();

        // 줄바꿈으로 분리 후 빈 줄 제거
        List<String> expanded = Arrays.stream(response.split("\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(5)
                .collect(Collectors.toList());

        // 원본 쿼리를 맨 앞에 추가 (검색 커버리지 유지)
        expanded.add(0, userQuery);

        log.debug("Query Expansion 완료 — {} queries: {}", expanded.size(), expanded);
        return expanded;
    }
}