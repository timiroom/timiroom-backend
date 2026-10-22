package com.rag.pipeline.phase1.search;

import com.rag.pipeline.common.dto.DocumentChunk;
import com.rag.pipeline.phase1.rl.SearchParams;
import com.rag.pipeline.phase1.rl.SearchRLService;
import com.rag.pipeline.phase1.session.SessionVectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
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
 * - 벡터 검색:   pgvector IVF_FLAT, cosine similarity 기반
 * - 키워드 검색: PostgreSQL FTS (영어) / trigram ILIKE (한국어)
 * - RRF 병합:   두 결과를 Reciprocal Rank Fusion으로 통합
 * - RL 튜닝:    SearchRLService가 epsilon-greedy로 파라미터를 선택하고
 *               사용자 피드백(수락/거절)에 따라 EMA로 자동 업데이트
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final SessionVectorStore sessionVectorStore;
    private final SearchRLService rlService;

    @Value("${app.rag.top-k-vector:20}")
    private int topKVector;

    @Value("${app.rag.top-k-keyword:20}")
    private int topKKeyword;

    // application.yml 기본값 — RL이 없을 때 또는 search/searchMultiple 직접 호출 시 사용
    @Value("${app.rag.rrf.vector-weight:1.0}")
    private double defaultVectorWeight;

    @Value("${app.rag.rrf.keyword-weight:1.0}")
    private double defaultKeywordWeight;

    @Value("${app.rag.search.similarity-threshold:0.3}")
    private double defaultSimilarityThreshold;

    @Value("${app.rag.search.min-threshold:0.1}")
    private double minThreshold;

    @Value("${app.rag.search.min-results:5}")
    private int minVectorResults;

    @Value("${app.rag.search.threshold-step:0.1}")
    private double thresholdStep;

    private static final int RRF_K = 60;


    // Phase1 검색 대상 타입 — ERD·API는 JSON 구조라 의미 벡터 품질이 낮고 노이즈가 됨
    // Spring AI 1.0.0-M3 PgVectorStore가 in 필터를 잘못된 jsonpath로 변환하므로
    // vectorSearch는 필터 없이 조회 후 Java에서 후처리 필터링 사용
    private static final Set<String> SEARCH_TYPE_SET =
            Set.of("prd", "market_research", "features");
    private static final String SEARCH_TYPE_SQL =
            "metadata->>'type' IN ('prd', 'market_research', 'features')";

    // ── public API ────────────────────────────────────────────────

    /** 단일 쿼리 Hybrid Search — 기본 파라미터 사용 (RL 미적용) */
    public List<DocumentChunk> search(String query, int topK) {
        log.debug("Hybrid Search 시작 — query: '{}', topK: {}", query, topK);
        List<DocumentChunk> result = searchInternal(query, topK, configParams());
        log.debug("Hybrid Search 완료 — {} docs 반환", result.size());
        return result;
    }

    /** 복수 쿼리 Hybrid Search — 기본 파라미터 사용 (RL 미적용) */
    public List<DocumentChunk> searchMultiple(List<String> queries, int topK) {
        return searchMultipleInternal(queries, topK, configParams());
    }

    /**
     * RL 연동 메인 진입점 — RagPipelineService에서 호출
     *
     * 1. SearchRLService.getParams()로 epsilon-greedy 파라미터 선택
     * 2. 파라미터로 Hybrid Search 수행
     * 3. 세션 PDF 청크가 있으면 RRF로 병합
     * 4. 결과를 RL 로그에 기록 → 피드백 대기
     */
    public List<DocumentChunk> searchWithSession(List<String> queries, String sessionId) {
        SearchParams params = rlService.getParams();
        log.debug("RL 파라미터 선택 — vw:{} kw:{} st:{}",
                params.vectorWeight(), params.keywordWeight(), params.similarityThreshold());

        List<DocumentChunk> globalResults = searchMultipleInternal(queries, topKVector, params);

        if (sessionId != null && sessionVectorStore.hasSession(sessionId)) {
            List<DocumentChunk> sessionChunks = sessionVectorStore.get(sessionId);
            // PDF 청크에 벡터+키워드 하이브리드 검색 수행 후 글로벌 결과와 RRF 병합
            List<DocumentChunk> sessionResults = sessionHybridSearch(queries, sessionChunks);
            log.debug("PDF 세션 검색 완료 — {} chunks 후보", sessionResults.size());
            globalResults = reciprocalRankFusion(
                    globalResults, sessionResults, topKVector,
                    params.vectorWeight(), params.keywordWeight());
        }

        rlService.logSearch(sessionId, params, globalResults.size());
        return globalResults;
    }

    // ── param-aware private search ────────────────────────────────

    private List<DocumentChunk> searchInternal(String query, int topK, SearchParams params) {
        List<DocumentChunk> vectorResults  = vectorSearch(query, params.similarityThreshold());
        List<DocumentChunk> keywordResults = keywordSearch(query);
        return reciprocalRankFusion(vectorResults, keywordResults, topK,
                params.vectorWeight(), params.keywordWeight());
    }

    private List<DocumentChunk> searchMultipleInternal(
            List<String> queries, int topK, SearchParams params) {

        Map<String, DocumentChunk> deduped = new LinkedHashMap<>();
        int[] contributions = new int[queries.size()];

        for (int qi = 0; qi < queries.size(); qi++) {
            List<DocumentChunk> results = searchInternal(queries.get(qi), topK, params);
            for (DocumentChunk chunk : results) {
                String id = chunk.getId().toString();
                if (!deduped.containsKey(id)) {
                    Map<String, Object> meta = new HashMap<>(chunk.getMetadata());
                    meta.put("source_query_index", qi);
                    deduped.put(id, DocumentChunk.builder()
                            .id(chunk.getId())
                            .content(chunk.getContent())
                            .metadata(meta)
                            .relevanceScore(chunk.getRelevanceScore())
                            .build());
                    contributions[qi]++;
                }
            }
        }

        for (int qi = 0; qi < queries.size(); qi++) {
            String q = queries.get(qi);
            log.debug("쿼리[{}] 기여 {}개 — '{}'",
                    qi, contributions[qi],
                    q.length() > 40 ? q.substring(0, 40) + "..." : q);
        }

        List<DocumentChunk> all = new ArrayList<>(deduped.values());
        return all.subList(0, Math.min(topK, all.size()));
    }

    // ── 벡터 검색 ─────────────────────────────────────────────────

    private List<DocumentChunk> vectorSearch(String query, double threshold) {
        double current = threshold;
        List<Document> docs = List.of();

        while (current >= minThreshold) {
            SearchRequest request = SearchRequest.query(query)
                    .withTopK(topKVector)
                    .withSimilarityThreshold(current);
            docs = vectorStore.similaritySearch(request).stream()
                    .filter(d -> SEARCH_TYPE_SET.contains(
                            String.valueOf(d.getMetadata().get("type"))))
                    .collect(Collectors.toList());

            if (docs.size() >= minVectorResults || current - thresholdStep < minThreshold) break;
            log.debug("벡터 검색 결과 {}개 부족 — 임계값 {} → {} 하향 조정",
                    docs.size(), current, String.format("%.1f", current - thresholdStep));
            current -= thresholdStep;
        }

        log.debug("벡터 검색 완료 — 임계값: {}, 결과: {}개", String.format("%.1f", current), docs.size());

        return docs.stream()
                .map(doc -> {
                    // Spring AI 1.0.0-M3: score는 metadata["distance"] (Float, 낮을수록 유사)
                    // relevanceScore = 1 - distance (높을수록 더 관련)
                    Object distObj = doc.getMetadata().get("distance");
                    double relevanceScore = distObj instanceof Number n
                            ? 1.0 - n.doubleValue()
                            : 0.5;
                    return DocumentChunk.builder()
                            .id(UUID.fromString(doc.getId()))
                            .content(doc.getContent())
                            .metadata(doc.getMetadata())
                            .relevanceScore(relevanceScore)
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ── 키워드 검색 ───────────────────────────────────────────────

    private List<DocumentChunk> keywordSearch(String query) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        String cleaned = query.replaceAll("[^a-zA-Z0-9가-힣\\s]", " ").trim();

        if (cleaned.matches(".*[가-힣]+.*")) {
            log.debug("한국어 쿼리 — trigram ILIKE 검색 사용: '{}'", query);
            return koreanKeywordSearch(cleaned);
        }

        String tsQuery = Arrays.stream(cleaned.trim().split("\\s+"))
                .filter(w -> !w.isBlank())
                .collect(Collectors.joining(" & "));

        if (tsQuery.isBlank()) return Collections.emptyList();

        String sql = """
                SELECT id, content, metadata,
                       ts_rank(tokens, to_tsquery('english', ?)) AS rank
                FROM document_chunks
                WHERE tokens @@ to_tsquery('english', ?)
                  AND """ + SEARCH_TYPE_SQL + """

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
                    tsQuery, tsQuery, topKKeyword);
        } catch (Exception e) {
            log.warn("키워드 검색 실패 — query: '{}', 원인: {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    private static final int MAX_KEYWORD_TERMS = 15;

    private List<DocumentChunk> koreanKeywordSearch(String query) {
        String[] terms = Arrays.stream(query.split("\\s+"))
                .filter(t -> !t.isBlank() && t.length() > 1)
                .limit(MAX_KEYWORD_TERMS)
                .toArray(String[]::new);

        if (terms.length == 0) return Collections.emptyList();

        String scoreExpr = Arrays.stream(terms)
                .map(t -> "CASE WHEN content ILIKE ? THEN 1 ELSE 0 END")
                .collect(Collectors.joining(" + "));

        String whereExpr = Arrays.stream(terms)
                .map(t -> "content ILIKE ?")
                .collect(Collectors.joining(" OR "));

        String sql = "SELECT id, content, metadata, (" + scoreExpr + ") * 1.0 / " + terms.length + " AS rank " +
                     "FROM document_chunks WHERE (" + whereExpr + ") AND " + SEARCH_TYPE_SQL +
                     " ORDER BY rank DESC LIMIT ?";

        List<Object> params = new ArrayList<>();
        for (String term : terms) params.add("%" + term + "%");
        for (String term : terms) params.add("%" + term + "%");
        params.add(topKKeyword);

        try {
            return jdbcTemplate.query(sql, (rs, rowNum) ->
                            DocumentChunk.builder()
                                    .id(UUID.fromString(rs.getString("id")))
                                    .content(rs.getString("content"))
                                    .metadata(Collections.emptyMap())
                                    .relevanceScore(rs.getDouble("rank"))
                                    .build(),
                    params.toArray());
        } catch (Exception e) {
            log.warn("한국어 키워드 검색 실패 — query: '{}', 원인: {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── 세션 PDF 청크 하이브리드 검색 ────────────────────────────

    /**
     * 세션 PDF 청크에 대해 벡터 검색 + 키워드 검색을 수행하고 RRF로 병합한다.
     *
     * 벡터 검색: 쿼리 임베딩 vs 청크 임베딩(저장된 float[]) cosine similarity
     * 키워드 검색: 쿼리 텀이 청크 내용에 포함되는 비율 (trigram 대체)
     * RRF로 두 결과를 병합 후 상위 K개 반환
     */
    private List<DocumentChunk> sessionHybridSearch(
            List<String> queries, List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) return List.of();

        // 1. 쿼리별 임베딩 계산 후 평균 — 다중 쿼리를 단일 벡터로 압축
        List<float[]> queryEmbeddings = queries.stream()
                .map(embeddingModel::embed)
                .collect(Collectors.toList());
        float[] queryVec = averageVectors(queryEmbeddings);

        // 2. 키워드 텀 집합
        Set<String> terms = queries.stream()
                .flatMap(q -> Arrays.stream(q.toLowerCase().split("\\s+")))
                .filter(t -> t.length() > 1)
                .collect(Collectors.toSet());

        // 3. 각 청크에 대해 벡터 점수 + 키워드 점수 계산
        List<DocumentChunk> vectorRanked = new ArrayList<>();
        List<DocumentChunk> keywordRanked = new ArrayList<>();

        for (DocumentChunk chunk : chunks) {
            // 벡터 점수: 청크에 임베딩이 있으면 cosine similarity, 없으면 0
            double vecScore = 0.0;
            if (chunk.getEmbedding() != null && chunk.getEmbedding().length > 0) {
                vecScore = cosineSimilarity(queryVec, chunk.getEmbedding());
            }

            // 키워드 점수: 텀 적중 비율
            double kwScore = 0.0;
            if (!terms.isEmpty()) {
                String lower = chunk.getContent().toLowerCase();
                long hits = terms.stream().filter(lower::contains).count();
                kwScore = (double) hits / terms.size();
            }

            if (vecScore > 0) {
                vectorRanked.add(DocumentChunk.builder()
                        .id(chunk.getId()).content(chunk.getContent())
                        .metadata(chunk.getMetadata()).relevanceScore(vecScore).build());
            }
            if (kwScore > 0) {
                keywordRanked.add(DocumentChunk.builder()
                        .id(chunk.getId()).content(chunk.getContent())
                        .metadata(chunk.getMetadata()).relevanceScore(kwScore).build());
            }
        }

        // 4. 점수 내림차순 정렬
        vectorRanked.sort(Comparator.comparingDouble(DocumentChunk::getRelevanceScore).reversed());
        keywordRanked.sort(Comparator.comparingDouble(DocumentChunk::getRelevanceScore).reversed());

        // 5. RRF 병합
        return reciprocalRankFusion(vectorRanked, keywordRanked, topKVector, 1.0, 1.0);
    }

    /** 여러 벡터의 평균 계산 */
    private float[] averageVectors(List<float[]> vecs) {
        if (vecs.isEmpty()) return new float[0];
        int dim = vecs.get(0).length;
        float[] avg = new float[dim];
        for (float[] v : vecs) {
            for (int i = 0; i < dim; i++) avg[i] += v[i];
        }
        for (int i = 0; i < dim; i++) avg[i] /= vecs.size();
        return avg;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot  += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        return (normA == 0 || normB == 0) ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ── RRF 병합 ──────────────────────────────────────────────────

    /**
     * Reciprocal Rank Fusion — score(d) = Σ weight / (k + rank_i(d))
     */
    private List<DocumentChunk> reciprocalRankFusion(
            List<DocumentChunk> vectorResults,
            List<DocumentChunk> keywordResults,
            int topK,
            double vw, double kw) {

        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, DocumentChunk> chunkMap = new HashMap<>();

        for (int i = 0; i < vectorResults.size(); i++) {
            DocumentChunk chunk = vectorResults.get(i);
            String id = chunk.getId().toString();
            scoreMap.merge(id, vw / (RRF_K + i + 1), Double::sum);
            chunkMap.putIfAbsent(id, chunk);
        }

        for (int i = 0; i < keywordResults.size(); i++) {
            DocumentChunk chunk = keywordResults.get(i);
            String id = chunk.getId().toString();
            scoreMap.merge(id, kw / (RRF_K + i + 1), Double::sum);
            chunkMap.putIfAbsent(id, chunk);
        }

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

    /** application.yml 값으로 구성한 기본 파라미터 */
    private SearchParams configParams() {
        return new SearchParams(defaultVectorWeight, defaultKeywordWeight, defaultSimilarityThreshold);
    }
}
