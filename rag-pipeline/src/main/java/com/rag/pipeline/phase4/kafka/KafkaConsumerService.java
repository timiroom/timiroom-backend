package com.rag.pipeline.phase4.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;

/**
 * Phase 4 — Kafka 메시지 소비 서비스
 *
 * Kafka 토픽에서 메시지를 소비하여 저장 처리합니다.
 *
 * @RetryableTopic — 처리 실패 시 자동 재시도 + DLQ 라우팅
 *   attempts: 3회 재시도
 *   backoff:  2초 간격
 *   dltTopicSuffix: 실패 시 .DLT 토픽으로 이동
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaConsumerService {

    /**
     * 파이프라인 결과 메시지 소비
     *
     * 처리 실패 시:
     *   1회 실패 → 2초 후 재시도
     *   2회 실패 → 2초 후 재시도
     *   3회 실패 → DLQ(rag.pipeline.result.DLT)로 이동
     */
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
                event.getPipelineId(),
                event.getUserQuery());

        try {
            processEvent(event);
            log.info("메시지 처리 완료 — pipelineId: {}", event.getPipelineId());

        } catch (Exception e) {
            log.error("메시지 처리 실패 — pipelineId: {}, 원인: {}",
                    event.getPipelineId(), e.getMessage());
            throw e; // 예외를 다시 던져야 RetryableTopic이 재시도함
        }
    }

    /**
     * DLQ 메시지 처리
     * 3회 재시도 후에도 실패한 메시지를 처리합니다.
     */
    @KafkaListener(
            topics = "${app.kafka.topics.dead-letter}",
            groupId = "${spring.kafka.consumer.group-id}-dlq"
    )
    public void consumeDlq(PipelineResultEvent event) {
        log.error("DLQ 메시지 수신 — pipelineId: {}, query: '{}'",
                event.getPipelineId(),
                event.getUserQuery());
        // TODO: 알림 발송 (Slack 등) 또는 수동 처리 큐에 적재
    }

    /**
     * 실제 저장 처리 로직
     * Phase 4에서 PostgreSQL, Neo4j 저장 로직이 여기에 추가됩니다.
     */
    private void processEvent(PipelineResultEvent event) {
        log.info("저장 처리 시작 — pipelineId: {}", event.getPipelineId());
        log.info("  featureList: {}", event.getFeatureList());
        log.info("  dbSchema 길이: {} chars", event.getDbSchema().length());
        log.info("  apiSpec 길이: {} chars", event.getApiSpec().length());

        // TODO: PostgreSQL 저장
        // TODO: Neo4j 저장
    }
}