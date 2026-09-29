package com.rag.pipeline.phase1;

import com.rag.pipeline.common.dto.DocumentChunk;
import com.rag.pipeline.phase1.chunking.SemanticChunkingService;
import com.rag.pipeline.phase1.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 외부 문서를 지식 베이스에 저장하는 진입점
 *
 * 흐름:
 *   텍스트 입력 → Semantic Chunking → 임베딩 → pgvector 저장
 *
 * 사용 예:
 *   API 문서, 요구사항 문서, 기술 스펙 등을
 *   RAG 검색이 가능하도록 사전에 저장할 때 사용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final SemanticChunkingService chunkingService;
    private final EmbeddingService        embeddingService;

    /**
     * 단일 문서 저장
     *
     * @param content  문서 전체 텍스트
     * @param metadata 문서 메타데이터 (예: {"source": "api-docs"})
     */
    public void ingest(String content, Map<String, Object> metadata) {
        log.info("문서 수집 시작 — source: {}",
                metadata.getOrDefault("source", "unknown"));

        // 1. Semantic Chunking
        List<DocumentChunk> chunks = chunkingService.chunk(content, metadata);
        log.debug("청킹 완료 — {} chunks 생성", chunks.size());

        // 2. 임베딩 + pgvector 저장
        embeddingService.embedAndStore(chunks);

        log.info("문서 수집 완료 — {} chunks 저장됨", chunks.size());
    }

    /**
     * 복수 문서 일괄 저장
     *
     * @param contents       문서 텍스트 목록
     * @param sharedMetadata 공통 메타데이터
     */
    public void ingestAll(List<String> contents, Map<String, Object> sharedMetadata) {
        log.info("일괄 문서 수집 시작 — {} 개 문서", contents.size());

        for (int i = 0; i < contents.size(); i++) {
            Map<String, Object> meta = new java.util.HashMap<>(sharedMetadata);
            meta.put("doc_index", i);
            ingest(contents.get(i), meta);
        }

        log.info("일괄 문서 수집 완료");
    }
}