package com.rag.pipeline.phase1.reranker;

import com.rag.pipeline.common.dto.DocumentChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Reranker 서비스
 *
 * Cohere API 키 있음 → Cohere Rerank 사용 (최고 품질)
 * Cohere API 키 없음 → GPT-4o-mini Rerank 사용 (대체)
 *
 * GPT-4o-mini Reranker:
 *   쿼리 + 후보 문서 목록을 GPT-4o-mini에 전달
 *   → "관련도 높은 순서로 번호 반환"
 *   → 번호 기준으로 재정렬
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerankerService {

    private final ChatClient.Builder chatClientBuilder;

    @Value("${app.rag.reranker.enabled:true}")
    private boolean rerankerEnabled;

    @Value("${app.rag.reranker.cohere-api-key:}")
    private String cohereApiKey;

    @Value("${app.rag.top-k-final:5}")
    private int topKFinal;

    private static final String RERANK_PROMPT = """
            당신은 검색 결과 관련도 평가 전문가입니다.
            아래 쿼리와 문서 목록을 보고, 쿼리와 관련도가 높은 순서로 문서 번호를 반환하세요.
            
            쿼리: %s
            
            문서 목록:
            %s
            
            규칙:
            - 관련도 높은 순서로 상위 %d개의 문서 번호만 반환하세요
            - 숫자만 쉼표로 구분하여 반환하세요 (예: 3,1,5,2,4)
            - 다른 텍스트는 절대 포함하지 마세요
            """;

    /**
     * 후보 청크를 쿼리 기준으로 재정렬하고 상위 K개 반환
     */
    public List<DocumentChunk> rerank(String query, List<DocumentChunk> candidates) {

        if (!rerankerEnabled || candidates.isEmpty()) {
            log.debug("Reranker 비활성화 — 후보 그대로 반환");
            return candidates.subList(0, Math.min(topKFinal, candidates.size()));
        }

        // Cohere 키 있으면 Cohere 사용
        if (cohereApiKey != null && !cohereApiKey.isBlank()) {
            log.debug("Cohere Reranker 사용");
            return rerankWithCohere(query, candidates);
        }

        // Cohere 키 없으면 GPT-4o-mini 사용
        log.debug("GPT-4o-mini Reranker 사용 (Cohere 키 없음)");
        return rerankWithGpt(query, candidates);
    }

    // ── GPT-4o-mini Reranker ──────────────────────────────────────

    private List<DocumentChunk> rerankWithGpt(String query, List<DocumentChunk> candidates) {
        try {
            // 문서 목록 번호 붙여서 조립
            StringBuilder docList = new StringBuilder();
            for (int i = 0; i < candidates.size(); i++) {
                String content = candidates.get(i).getContent();
                // 너무 길면 앞 200자만
                if (content.length() > 200) content = content.substring(0, 200) + "...";
                docList.append(i + 1).append(". ").append(content).append("\n");
            }

            ChatClient chatClient = chatClientBuilder
                    .defaultOptions(
                            org.springframework.ai.openai.OpenAiChatOptions.builder()
                                    .withModel("gpt-4o-mini")
                                    .withTemperature(0.0)
                                    .build()
                    )
                    .build();

            String response = chatClient
                    .prompt(String.format(RERANK_PROMPT,
                            query,
                            docList.toString(),
                            topKFinal))
                    .call()
                    .content();

            // 응답 파싱 (예: "3,1,5,2,4")
            List<DocumentChunk> reranked = new ArrayList<>();
            String[] parts = response.trim().split(",");
            for (String part : parts) {
                try {
                    int idx = Integer.parseInt(part.trim()) - 1;
                    if (idx >= 0 && idx < candidates.size()) {
                        reranked.add(candidates.get(idx));
                    }
                } catch (NumberFormatException e) {
                    log.warn("GPT Reranker 응답 파싱 실패 — part: {}", part);
                }
            }

            // 파싱 실패 시 fallback
            if (reranked.isEmpty()) {
                log.warn("GPT Reranker 결과 없음 — fallback 적용");
                return candidates.subList(0, Math.min(topKFinal, candidates.size()));
            }

            log.info("GPT Reranker 완료 — {} docs 반환", reranked.size());
            return reranked;

        } catch (Exception e) {
            log.error("GPT Reranker 실패 — {} — fallback 적용", e.getMessage());
            return candidates.subList(0, Math.min(topKFinal, candidates.size()));
        }
    }

    // ── Cohere Reranker ───────────────────────────────────────────

    private List<DocumentChunk> rerankWithCohere(String query, List<DocumentChunk> candidates) {
        try {
            List<String> documents = candidates.stream()
                    .map(DocumentChunk::getContent)
                    .toList();

            CohereRerankRequest request = new CohereRerankRequest(
                    "rerank-english-v3.0", query, documents, topKFinal);

            CohereRerankResponse response = org.springframework.web.client.RestClient.create()
                    .post()
                    .uri("https://api.cohere.ai/v1/rerank")
                    .header("Authorization", "Bearer " + cohereApiKey)
                    .header("Content-Type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(CohereRerankResponse.class);

            if (response == null || response.results() == null) {
                log.warn("Cohere 응답 없음 — fallback 적용");
                return candidates.subList(0, Math.min(topKFinal, candidates.size()));
            }

            return response.results().stream()
                    .sorted(java.util.Comparator
                            .comparingDouble(CohereResult::relevanceScore).reversed())
                    .map(result -> {
                        DocumentChunk original = candidates.get(result.index());
                        return DocumentChunk.builder()
                                .id(original.getId())
                                .content(original.getContent())
                                .metadata(original.getMetadata())
                                .relevanceScore(result.relevanceScore())
                                .build();
                    })
                    .toList();

        } catch (Exception e) {
            log.error("Cohere Reranker 실패 — {} — GPT fallback 적용", e.getMessage());
            return rerankWithGpt(query, candidates);
        }
    }

    // ── Cohere DTO ────────────────────────────────────────────────

    record CohereRerankRequest(
            String model, String query,
            java.util.List<String> documents,
            @com.fasterxml.jackson.annotation.JsonProperty("top_n") int topN) {}

    record CohereRerankResponse(java.util.List<CohereResult> results) {}

    record CohereResult(
            int index,
            @com.fasterxml.jackson.annotation.JsonProperty("relevance_score")
            double relevanceScore) {}
}