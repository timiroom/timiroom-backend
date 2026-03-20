package com.rag.pipeline.phase1.search;

import com.rag.pipeline.common.dto.DocumentChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * STEP 3 — Hybrid Search (벡터 검색 + 키워드 검색 + RRF 병합)
 *
 * - 벡터 검색:   pgvector HNSW, cosine similarity 기반
 * - 키워드 검색: PostgreSQL FTS, BM25 기반
 * - RRF 병합:   두 결과를 단일 정렬 리스트로 통합
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.rag.top-k-vector:20}")
    private int topKVector;

    @Value("${app.rag.top-k-keyword:20}")
    private int topKKeyword;

    // RRF 상수 (보통 60 사용)
    private static final int RRF_K = 60;

    /**
     * 단일 쿼리로 Hybrid Search 수행
     */
    public List<DocumentChunk> search(String query, int topK) {
        log.debug("Hybrid Search 시작 — query: '{}', topK: {}", query, topK);

        List<DocumentChunk> vectorResults  = vectorSearch(query);
        List<DocumentChunk> keywordResults = keywordSearch(query);

        List<DocumentChunk> merged = reciprocalRankFusion(vectorResults, keywordResults, topK);

        log.debug("Hybrid Search 완료 — {} docs 반환 (vector: {}, keyword: {})",
                merged.size(), vectorResults.size(), keywordResults.size());
        return merged;
    }

    /**
     * 복수 쿼리(Query Expansion 결과)로 Hybrid Search 수행 후 중복 제거
     */
    public List<DocumentChunk> searchMultiple(List<String> queries, int topK) {
        // LinkedHashMap으로 순서 유지하면서 중복 제거
        Map<String, DocumentChunk> deduped = new LinkedHashMap<>();

        for (String query : queries) {
            List<DocumentChunk> results = search(query, topK);
            for (DocumentChunk chunk : results) {
                // 동일 ID는 먼저 들어온 것(높은 순위) 유지
                deduped.putIfAbsent(chunk.getId().toString(), chunk);
            }
        }

        List<DocumentChunk> all = new ArrayList<>(deduped.values());
        return all.subList(0, Math.min(topK, all.size()));
    }

    // ── 벡터 검색 ─────────────────────────────────────────────────

    private List<DocumentChunk> vectorSearch(String query) {
        SearchRequest request = SearchRequest.query(query)
                .withTopK(topKVector)
                .withSimilarityThreshold(0.5);

        List<Document> docs = vectorStore.similaritySearch(request);

        return docs.stream()
                .map(doc -> DocumentChunk.builder()
                        .id(UUID.fromString(doc.getId()))
                        .content(doc.getContent())
                        .metadata(doc.getMetadata())
                        .relevanceScore(((Number) doc.getMetadata()
                                .getOrDefault("distance", 0.0)).doubleValue())
                        .build())
                .collect(Collectors.toList());
    }

    // ── 키워드 검색 (PostgreSQL FTS) ──────────────────────────────

    private List<DocumentChunk> keywordSearch(String query) {
        // 공백을 & 로 연결해서 tsquery 형식으로 변환
        String tsQuery = Arrays.stream(query.trim().split("\\s+"))
                .collect(Collectors.joining(" & "));

        String sql = """
                SELECT id, content, metadata,
                       ts_rank(to_tsvector('english', content),
                               to_tsquery('english', ?)) AS rank
                FROM document_chunks
                WHERE to_tsvector('english', content) @@ to_tsquery('english', ?)
                ORDER BY rank DESC
                LIMIT ?
                """;

        try {
            return jdbcTemplate.query(sql, (rs, rowNum) ->
                            DocumentChunk.builder()
                                    .id(UUID.fromString(rs.getString("id")))
                                    .content(rs.getString("content"))
                                    .metadata(Collections.emptyMap())
                                    .relevanceScore(rs.getDouble("rank"))
                                    .build(),
                    tsQuery, tsQuery, topKKeyword
            );
        } catch (Exception e) {
            log.warn("키워드 검색 실패 — query: '{}', 원인: {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── RRF 병합 ──────────────────────────────────────────────────

    /**
     * Reciprocal Rank Fusion
     * score(d) = Σ 1 / (k + rank_i(d))
     */
    private List<DocumentChunk> reciprocalRankFusion(
            List<DocumentChunk> vectorResults,
            List<DocumentChunk> keywordResults,
            int topK) {

        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, DocumentChunk> chunkMap = new HashMap<>();

        // 벡터 검색 결과 점수 부여
        for (int i = 0; i < vectorResults.size(); i++) {
            DocumentChunk chunk = vectorResults.get(i);
            String id = chunk.getId().toString();
            scoreMap.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            chunkMap.putIfAbsent(id, chunk);
        }

        // 키워드 검색 결과 점수 부여
        for (int i = 0; i < keywordResults.size(); i++) {
            DocumentChunk chunk = keywordResults.get(i);
            String id = chunk.getId().toString();
            scoreMap.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            chunkMap.putIfAbsent(id, chunk);
        }

        // RRF 점수 기준 내림차순 정렬 후 topK 반환
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> DocumentChunk.builder()
                        .id(chunkMap.get(e.getKey()).getId())
                        .content(chunkMap.get(e.getKey()).getContent())
                        .metadata(chunkMap.get(e.getKey()).getMetadata())
                        .relevanceScore(e.getValue())
                        .build())
                .collect(Collectors.toList());
    }
}