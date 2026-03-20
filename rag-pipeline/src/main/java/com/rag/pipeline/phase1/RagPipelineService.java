package com.rag.pipeline.phase1;

import com.rag.pipeline.common.dto.DocumentChunk;
import com.rag.pipeline.phase1.reranker.RerankerService;
import com.rag.pipeline.phase1.search.HybridSearchService;
import com.rag.pipeline.phase1.search.QueryExpansionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Phase 1 — 전체 RAG 파이프라인 오케스트레이터
 *
 * STEP 1. 채팅 입력 수신
 * STEP 2. Query Expansion    (GPT-4o-mini)
 * STEP 3. Hybrid Search      (pgvector + PostgreSQL FTS + RRF)
 * STEP 4. Reranker           (Cohere Rerank API)
 * STEP 5. Semantic Context   조립 → Phase 2 입력 프롬프트 완성
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagPipelineService {

    private final QueryExpansionService queryExpansionService;
    private final HybridSearchService   hybridSearchService;
    private final RerankerService       rerankerService;

    @Value("${app.rag.top-k-vector:20}")
    private int topKHybrid;

    private static final String CONTEXT_TEMPLATE = """
            당신은 소프트웨어 아키텍트입니다.
            아래 참조 문서와 사용자 요청을 바탕으로 분석하세요.
            
            [참조 문서]
            %s
            
            [사용자 요청]
            %s
            
            참조 문서를 최대한 활용하여 요청을 분석하고
            필요한 기능과 설계 방향을 도출하세요.
            """;

    /**
     * Phase 1 전체 실행
     * 사용자 쿼리 → 컨텍스트가 풍부한 프롬프트 반환
     *
     * @param userQuery 사용자 원본 입력
     * @return Phase 2 PM 에이전트에 전달할 완성된 프롬프트
     */
    public String buildContext(String userQuery) {
        log.info("=== Phase 1 시작 === query: '{}'", userQuery);

        // STEP 2 — Query Expansion
        List<String> expandedQueries = queryExpansionService.expand(userQuery);
        log.info("STEP 2 완료 — {} queries 생성", expandedQueries.size());

        // STEP 3 — Hybrid Search
        List<DocumentChunk> hybridResults = hybridSearchService
                .searchMultiple(expandedQueries, topKHybrid);
        log.info("STEP 3 완료 — {} candidates 검색", hybridResults.size());

        // STEP 4 — Reranker
        List<DocumentChunk> rerankedChunks = rerankerService
                .rerank(userQuery, hybridResults);
        log.info("STEP 4 완료 — {} chunks 최종 선택", rerankedChunks.size());

        // STEP 5 — Context 조립
        String context = buildContextString(rerankedChunks);
        String finalPrompt = String.format(CONTEXT_TEMPLATE, context, userQuery);

        log.info("=== Phase 1 완료 === context 길이: {} chars", context.length());
        return finalPrompt;
    }

    /**
     * 청크 목록을 하나의 컨텍스트 문자열로 조립
     */
    private String buildContextString(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) {
            return "관련 참조 문서 없음";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            double score = chunk.getRelevanceScore() != null
                    ? chunk.getRelevanceScore() : 0.0;

            sb.append(String.format("--- 문서 %d (관련도: %.3f) ---\n", i + 1, score));
            sb.append(chunk.getContent());
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }
}