package com.rag.pipeline.phase1.reranker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.pipeline.common.dto.DocumentChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reranker 서비스
 *
 * Cohere API 키 있음 → Cohere-rerank-v4.0-pro 사용 (Azure AI Foundry)
 * Cohere API 키 없음 → gpt-5.4-mini Rerank 사용 (대체)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerankerService {

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${app.rag.reranker.enabled:true}")
    private boolean rerankerEnabled;

    @Value("${app.rag.reranker.cohere-api-key:}")
    private String cohereApiKey;

    @Value("${spring.ai.openai.api-key}")
    private String foundryApiKey;

    @Value("${app.rag.top-k-final:5}")
    private int topKFinal;

    private static final String FOUNDRY_RESPONSES_URL =
            "https://align-it-resource.services.ai.azure.com/openai/v1/responses";

    private static final String COHERE_RERANK_URL =
            "https://align-it-resource.services.ai.azure.com/providers/cohere/v2/rerank";

    private static final String RERANK_INSTRUCTIONS =
            "당신은 검색 결과 관련도 평가 전문가입니다.\n" +
            "규칙:\n" +
            "- 관련도 높은 순서로 상위 N개의 문서 번호만 반환하세요\n" +
            "- 숫자만 쉼표로 구분하여 반환하세요 (예: 3,1,5,2,4)\n" +
            "- 다른 텍스트는 절대 포함하지 마세요";

    /**
     * 후보 청크를 쿼리 기준으로 재정렬하고 상위 K개 반환
     */
    public List<DocumentChunk> rerank(String query, List<DocumentChunk> candidates) {

        if (!rerankerEnabled) {
            log.info("Reranker 비활성화 (app.rag.reranker.enabled=false) — 후보 그대로 반환");
            return candidates.subList(0, Math.min(topKFinal, candidates.size()));
        }
        if (candidates.isEmpty()) {
            log.info("Reranker 건너뜀 — HybridSearch 결과 0개 (지식베이스 미매칭)");
            return List.of();
        }

        if (cohereApiKey != null && !cohereApiKey.isBlank()) {
            log.debug("Cohere Reranker 사용");
            return rerankWithCohere(query, candidates);
        }

        log.debug("gpt-5.4-mini Reranker 사용 (Cohere 키 없음)");
        return rerankWithGpt(query, candidates);
    }

    // ── gpt-5.4-mini Reranker ───────────────────────────────────────

    private List<DocumentChunk> rerankWithGpt(String query, List<DocumentChunk> candidates) {
        try {
            StringBuilder docList = new StringBuilder();
            for (int i = 0; i < candidates.size(); i++) {
                String content = candidates.get(i).getContent();
                if (content.length() > 200) content = content.substring(0, 200) + "...";
                docList.append(i + 1).append(". ").append(content).append("\n");
            }

            String userMessage = String.format(
                    "쿼리: %s\n\n문서 목록:\n%s\n\n관련도 높은 순서로 상위 %d개의 문서 번호를 반환하세요.",
                    query, docList, topKFinal);

            Map<String, Object> payload = Map.of(
                    "model", "gpt-5.4-mini",
                    "instructions", RERANK_INSTRUCTIONS,
                    "input", List.of(Map.of("role", "user", "content", userMessage)),
                    "max_output_tokens", 200,
                    "temperature", 0.0
            );

            String raw = restClientBuilder.build()
                    .post()
                    .uri(FOUNDRY_RESPONSES_URL)
                    .header("Authorization", "Bearer " + foundryApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            String response = extractText(objectMapper.readTree(raw));

            List<DocumentChunk> reranked = new ArrayList<>();
            for (String part : response.trim().split(",")) {
                try {
                    int idx = Integer.parseInt(part.trim()) - 1;
                    if (idx >= 0 && idx < candidates.size()) {
                        reranked.add(candidates.get(idx));
                    }
                } catch (NumberFormatException e) {
                    log.warn("GPT Reranker 응답 파싱 실패 — part: {}", part);
                }
            }

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
                    "Cohere-rerank-v4.0-pro", query, documents, topKFinal);

            // Cohere API는 Content-Length 헤더 필수 → JSON 문자열로 직렬화 후 byte[] 전송
            byte[] bodyBytes = objectMapper.writeValueAsBytes(request);

            CohereRerankResponse response = restClientBuilder.build()
                    .post()
                    .uri(COHERE_RERANK_URL)
                    .header("api-key", cohereApiKey)
                    .header("Content-Length", String.valueOf(bodyBytes.length))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bodyBytes)
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

    // ── 공통 ─────────────────────────────────────────────────────

    private String extractText(JsonNode root) {
        for (JsonNode item : root.path("output")) {
            if ("message".equals(item.path("type").asText())) {
                for (JsonNode block : item.path("content")) {
                    if ("output_text".equals(block.path("type").asText())) {
                        return block.path("text").asText();
                    }
                }
            }
        }
        return "";
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
