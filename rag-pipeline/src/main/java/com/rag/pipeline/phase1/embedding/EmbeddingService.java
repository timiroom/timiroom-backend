package com.rag.pipeline.phase1.embedding;

import com.rag.pipeline.common.dto.DocumentChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * STEP 3 — 임베딩 생성 및 pgvector 저장
 *
 * Spring AI VectorStore를 통해:
 *   1. text-embedding-3-large로 각 청크를 3072차원 벡터로 변환
 *   2. pgvector(HNSW 인덱스)에 저장
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    /** Spring AI가 자동 구성하는 PgVectorStore */
    private final VectorStore vectorStore;

    /**
     * 청크 목록을 임베딩 후 pgvector에 저장
     *
     * @param chunks SemanticChunkingService가 생성한 청크 목록
     */
    public void embedAndStore(List<DocumentChunk> chunks) {
        log.debug("임베딩 저장 시작 — {} chunks", chunks.size());

        // DocumentChunk → Spring AI Document 변환
        List<Document> documents = chunks.stream()
                .map(chunk -> new Document(
                        chunk.getId().toString(),
                        chunk.getContent(),
                        chunk.getMetadata()
                ))
                .collect(Collectors.toList());

        // VectorStore.add() — 내부적으로 text-embedding-3-large 호출 후 pgvector 저장
        vectorStore.add(documents);

        log.info("임베딩 저장 완료 — {} chunks 저장됨", documents.size());
    }

    /**
     * 단일 텍스트를 바로 저장 (소규모 문서용)
     */
    public void embedAndStoreSingle(String content, Map<String, Object> metadata) {
        Document doc = new Document(content, metadata);
        vectorStore.add(List.of(doc));
        log.debug("단일 문서 임베딩 저장 완료");
    }
}
