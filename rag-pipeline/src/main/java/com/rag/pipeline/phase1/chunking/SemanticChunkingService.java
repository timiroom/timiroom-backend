package com.rag.pipeline.phase1.chunking;

import com.rag.pipeline.common.dto.DocumentChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * STEP 3 (저장 시점) — Semantic Chunking
 *
 * 고정 크기로 자르지 않고 문장 간 cosine similarity가
 * 떨어지는 의미 경계에서 분할합니다.
 *
 * 알고리즘:
 *   1. 문서를 문장 단위로 분리
 *   2. 인접 문장 쌍의 cosine similarity 계산
 *   3. similarity가 threshold 이하로 떨어지는 지점을 경계로 분할
 *   4. 최대 크기 초과 시 추가 분할
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticChunkingService {

    private final EmbeddingModel embeddingModel;

    @Value("${app.rag.chunk-size:512}")
    private int maxChunkSize;

    @Value("${app.rag.chunk-overlap:64}")
    private int chunkOverlap;

    // 이 값 이하로 similarity가 떨어지면 청크 경계로 판단
    private static final double SIMILARITY_THRESHOLD = 0.75;

    /**
     * 문서 텍스트를 의미 단위 청크 목록으로 변환
     *
     * @param text     원본 문서 텍스트
     * @param metadata 청크에 공통으로 붙일 메타데이터
     * @return DocumentChunk 리스트
     */
    public List<DocumentChunk> chunk(String text, Map<String, Object> metadata) {
        log.debug("Semantic Chunking 시작 — 문서 길이: {} chars", text.length());

        // 1. 문장 분리
        List<String> sentences = splitSentences(text);
        if (sentences.size() <= 1) {
            return List.of(buildChunk(text, metadata));
        }

        // 2. 각 문장 임베딩 계산
        List<float[]> embeddings = embedSentences(sentences);

        // 3. 인접 문장 similarity 기반 경계 탐지
        List<Integer> boundaries = detectBoundaries(embeddings);

        // 4. 경계에 따라 청크 조립
        List<DocumentChunk> chunks = assembleChunks(sentences, boundaries, metadata);

        log.debug("Semantic Chunking 완료 — {} chunks 생성", chunks.size());
        return chunks;
    }

    // ── private 메서드 ────────────────────────────────────────────

    /** 문장 분리 (마침표/줄바꿈 기준) */
    private List<String> splitSentences(String text) {
        String[] raw = text.split("(?<=[.!?]\\s)|\\n{2,}");
        List<String> result = new ArrayList<>();
        for (String s : raw) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /** 각 문장을 임베딩 벡터로 변환 */
    private List<float[]> embedSentences(List<String> sentences) {
        List<float[]> result = new ArrayList<>();
        for (String sentence : sentences) {
            float[] embedding = embeddingModel.embed(sentence);
            result.add(embedding);
        }
        return result;
    }

    /** similarity가 낮아지는 지점을 경계로 탐지 */
    private List<Integer> detectBoundaries(List<float[]> embeddings) {
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);

        for (int i = 0; i < embeddings.size() - 1; i++) {
            double similarity = cosineSimilarity(embeddings.get(i), embeddings.get(i + 1));
            if (similarity < SIMILARITY_THRESHOLD) {
                boundaries.add(i + 1);
                log.debug("경계 감지 — 문장 {} (similarity: {})", i + 1, String.format("%.3f", similarity));
            }
        }
        boundaries.add(embeddings.size());
        return boundaries;
    }

    /** 경계 기준으로 문장들을 청크로 조립 */
    private List<DocumentChunk> assembleChunks(
            List<String> sentences,
            List<Integer> boundaries,
            Map<String, Object> metadata) {

        List<DocumentChunk> chunks = new ArrayList<>();

        for (int i = 0; i < boundaries.size() - 1; i++) {
            int start = boundaries.get(i);
            int end = boundaries.get(i + 1);

            StringBuilder sb = new StringBuilder();
            for (int j = start; j < end; j++) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(sentences.get(j));
            }

            String chunkText = sb.toString();

            // 최대 크기 초과 시 강제 분할
            if (chunkText.length() > maxChunkSize) {
                chunks.addAll(splitBySize(chunkText, metadata));
            } else {
                chunks.add(buildChunk(chunkText, metadata));
            }
        }

        return chunks;
    }

    /** 최대 크기 초과 시 강제 분할 (fallback) */
    private List<DocumentChunk> splitBySize(String text, Map<String, Object> metadata) {
        List<DocumentChunk> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChunkSize, text.length());
            result.add(buildChunk(text.substring(start, end), metadata));
            start = end - chunkOverlap;
            if (start < 0) start = 0;
        }
        return result;
    }

    /** DocumentChunk 객체 생성 */
    private DocumentChunk buildChunk(String content, Map<String, Object> metadata) {
        return DocumentChunk.builder()
                .id(UUID.randomUUID())
                .content(content)
                .metadata(new HashMap<>(metadata))
                .build();
    }

    /** 두 벡터의 cosine similarity 계산 */
    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}