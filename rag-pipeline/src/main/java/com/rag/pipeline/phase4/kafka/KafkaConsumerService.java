package com.rag.pipeline.phase4.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 4 — Kafka 메시지 소비 서비스
 *
 * 파이프라인 결과를 수신하여 document_chunks 테이블에 저장합니다.
 * 저장된 데이터는 다음 파이프라인 실행 시 HybridSearch에서 활용됩니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaConsumerService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;  // Spring AI 임베딩 모델

    // 청크 최대 글자수 (너무 크면 임베딩 품질 저하)
    private static final int CHUNK_SIZE = 800;

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 2000),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltTopicSuffix = ".DLT"
    )
    @KafkaListener(
            topics = "${app.kafka.topics.pipeline-result}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(PipelineResultEvent event) {
        log.info("Kafka 메시지 수신 — pipelineId: {}, query: '{}'",
                event.getPipelineId(), event.getUserQuery());
        try {
            processEvent(event);
            log.info("메시지 처리 완료 — pipelineId: {}", event.getPipelineId());
        } catch (Exception e) {
            log.error("메시지 처리 실패 — pipelineId: {}, 원인: {}",
                    event.getPipelineId(), e.getMessage());
            throw e;
        }
    }

    @KafkaListener(
            topics = "${app.kafka.topics.dead-letter}",
            groupId = "${spring.kafka.consumer.group-id}-dlq"
    )
    public void consumeDlq(PipelineResultEvent event) {
        log.error("DLQ 메시지 수신 — pipelineId: {}, query: '{}'",
                event.getPipelineId(), event.getUserQuery());
        // TODO: Slack 알림 등
    }

    /**
     * 파이프라인 결과를 document_chunks 테이블에 저장
     */
    private void processEvent(PipelineResultEvent event) {
        log.info("저장 처리 시작 — pipelineId: {}", event.getPipelineId());

        int totalSaved = 0;

        // 1. PRD 문서 저장
        if (event.getPrdDocument() != null && !event.getPrdDocument().isBlank()) {
            int saved = saveChunks(event.getPrdDocument(), "prd",
                    event.getPipelineId(), event.getUserQuery());
            log.info("  PRD 저장 완료 — {}개 청크", saved);
            totalSaved += saved;
        }

        // 2. 시장 조사 데이터 저장
        if (event.getMarketResearch() != null && !event.getMarketResearch().isBlank()) {
            int saved = saveChunks(event.getMarketResearch(), "market_research",
                    event.getPipelineId(), event.getUserQuery());
            log.info("  시장 데이터 저장 완료 — {}개 청크", saved);
            totalSaved += saved;
        }

        // 3. DB 스키마 저장
        if (event.getDbSchema() != null && !event.getDbSchema().isBlank()) {
            int saved = saveChunks(event.getDbSchema(), "erd",
                    event.getPipelineId(), event.getUserQuery());
            log.info("  ERD 저장 완료 — {}개 청크", saved);
            totalSaved += saved;
        }

        // 4. API 명세 저장
        if (event.getApiSpec() != null && !event.getApiSpec().isBlank()) {
            int saved = saveChunks(event.getApiSpec(), "api",
                    event.getPipelineId(), event.getUserQuery());
            log.info("  API 저장 완료 — {}개 청크", saved);
            totalSaved += saved;
        }

        log.info("저장 처리 완료 — pipelineId: {}, 총 {}개 청크 저장",
                event.getPipelineId(), totalSaved);
    }

    /**
     * 텍스트를 청크로 분할하여 document_chunks에 저장
     */
    private int saveChunks(String text, String type,
                           String pipelineId, String userQuery) {
        List<String> chunks = splitIntoChunks(text, CHUNK_SIZE);
        int saved = 0;

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            try {
                // 임베딩 생성 (vector(3072) — text-embedding-3-large)
                float[] embeddingArr = embeddingModel.embed(chunk);
                String embeddingStr = toVectorString(embeddingArr);

                // metadata JSON 구성
                String metadata = String.format(
                        "{\"type\":\"%s\",\"pipeline_id\":\"%s\"," +
                                "\"query\":\"%s\",\"chunk_index\":%d}",
                        type,
                        pipelineId,
                        userQuery.replace("\"", "\\\""),
                        i
                );

                // document_chunks 테이블에 INSERT
                jdbcTemplate.update("""
                    INSERT INTO document_chunks (content, metadata, embedding)
                    VALUES (?, ?::jsonb, ?::vector)
                    """,
                        chunk,
                        metadata,
                        embeddingStr
                );
                saved++;

            } catch (Exception e) {
                log.warn("청크 저장 실패 — type: {}, index: {}, 원인: {}",
                        type, i, e.getMessage());
            }
        }
        return saved;
    }

    /**
     * 텍스트를 CHUNK_SIZE 단위로 분할
     */
    private List<String> splitIntoChunks(String text, int size) {
        List<String> chunks = new ArrayList<>();
        int len = text.length();
        for (int i = 0; i < len; i += size) {
            chunks.add(text.substring(i, Math.min(i + size, len)));
        }
        return chunks;
    }

    /**
     * float[] → PostgreSQL vector 형식 문자열
     * 예: [0.1, 0.2, 0.3, ...]
     */
    private String toVectorString(float[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            sb.append(arr[i]);
            if (i < arr.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}