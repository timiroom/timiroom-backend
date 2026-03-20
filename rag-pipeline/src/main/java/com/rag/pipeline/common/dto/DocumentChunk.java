package com.rag.pipeline.common.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;
import java.util.UUID;

/**
 * Phase 1 전체에서 사용되는 문서 청크 데이터 객체
 *
 * - SemanticChunkingService  → 청크 생성 시 사용
 * - EmbeddingService         → pgvector 저장 시 사용
 * - HybridSearchService      → 검색 결과 반환 시 사용
 * - RerankerService          → 재정렬 결과 반환 시 사용
 */
@Getter
@Builder
public class DocumentChunk {

    /** 청크 고유 ID */
    private UUID id;

    /** 청크 텍스트 내용 */
    private String content;

    /** 청크 메타데이터 (파일명, 소스 등) */
    private Map<String, Object> metadata;

    /**
     * Reranker가 계산한 관련도 점수
     * - Hybrid Search 단계에서는 RRF 점수
     * - Reranker 단계 이후에는 Cohere relevance score
     */
    private Double relevanceScore;
}