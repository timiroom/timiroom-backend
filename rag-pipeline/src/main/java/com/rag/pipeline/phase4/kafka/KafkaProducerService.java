package com.rag.pipeline.phase4.kafka;

import com.rag.pipeline.phase2.state.PipelineState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Phase 4 — Kafka 메시지 발행 서비스
 *
 * Phase 3 검증 통과 후 결과를 Kafka 토픽에 발행합니다.
 * 발행 성공/실패 콜백으로 안정성을 확보합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaProducerService {

    private final KafkaTemplate<String, PipelineResultEvent> kafkaTemplate;

    @Value("${app.kafka.topics.pipeline-result}")
    private String topic;

    /**
     * Phase 3 결과를 Kafka 토픽에 발행
     *
     * @param state Phase 3 검증 통과한 State
     * @return 발행된 이벤트의 pipelineId
     */
    public String publish(PipelineState state) {
        String pipelineId = UUID.randomUUID().toString();

        PipelineResultEvent event = PipelineResultEvent.builder()
                .pipelineId(pipelineId)
                .userQuery(state.getUserQuery())
                .featureList(state.getFeatureList())
                .dbSchema(state.getDbSchema())
                .apiSpec(state.getApiSpec())
                .retryCount(state.getRetryCount())
                .createdAt(LocalDateTime.now())
                .build();

        // 비동기 발행 — pipelineId를 파티션 키로 사용 (순서 보장)
        CompletableFuture<SendResult<String, PipelineResultEvent>> future =
                kafkaTemplate.send(topic, pipelineId, event);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Kafka 발행 성공 — pipelineId: {}, offset: {}",
                        pipelineId,
                        result.getRecordMetadata().offset());
            } else {
                log.error("Kafka 발행 실패 — pipelineId: {}, 원인: {}",
                        pipelineId, ex.getMessage());
            }
        });

        log.info("Kafka 발행 요청 완료 — pipelineId: {}", pipelineId);
        return pipelineId;
    }
}