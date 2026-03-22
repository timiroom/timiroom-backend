package com.rag.pipeline.phase2;

import com.rag.pipeline.common.response.ApiResponse;
import com.rag.pipeline.phase2.dto.GenerateRequest;
import com.rag.pipeline.phase2.dto.GenerateResponse;
import com.rag.pipeline.phase2.graph.OrchestrationGraph;
import com.rag.pipeline.phase2.state.PipelineState;
import com.rag.pipeline.phase1.RagPipelineService;
import com.rag.pipeline.phase3.validation.ValidationService;
import com.rag.pipeline.phase3.retry.RetryService;
import com.rag.pipeline.phase4.kafka.KafkaProducerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orchestration")
@RequiredArgsConstructor
@Slf4j
public class OrchestrationController {

    private final RagPipelineService  ragPipelineService;
    private final OrchestrationGraph  orchestrationGraph;
    private final ValidationService   validationService;
    private final RetryService        retryService;
    private final KafkaProducerService kafkaProducerService;
    private final ObjectMapper        objectMapper;

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<?>> generate(
            @RequestBody @Valid GenerateRequest request) {

        String pipelineId = UUID.randomUUID().toString();
        MDC.put("pipelineId", pipelineId.substring(0, 8));

        try {
            log.info("파이프라인 시작 | query: {}", request.getQuery());

            // ── Phase 1 — RAG 컨텍스트 생성 ─────────────────────────
            String contextPrompt = ragPipelineService.buildContext(request.getQuery());
            log.debug("Phase 1 완료 | contextPrompt 생성됨");

            // ── Phase 2 — 멀티 에이전트 실행 ────────────────────────
            PipelineState phase2State = orchestrationGraph.execute(
                    request.getQuery(), contextPrompt
            );
            log.debug("Phase 2 완료 | retryCount: {}", phase2State.getRetryCount());

            // ── Phase 3 — 형식 검증 ─────────────────────────────────
            PipelineState validatedState = validationService.validate(phase2State);

            if (!validatedState.isValidated()) {
                log.warn("Phase 3 검증 실패 — RetryService 진입");
                validatedState = retryService.retry(validatedState);
            }

            // HITL 여부 확인 (최대 재시도 초과 시)
            if (!validatedState.isValidated()) {
                log.warn("HITL 요청 | 자동 검증 실패");
                return ResponseEntity.accepted()
                        .body(ApiResponse.error(
                                "PIPELINE_003",
                                "자동 검증 실패 — 관리자 검토가 필요합니다"
                        ));
            }

            // ── Phase 4 — Kafka 발행 ────────────────────────────────
            String kafkaPipelineId = kafkaProducerService.publish(validatedState);
            log.info("Phase 4 완료 | Kafka 발행 pipelineId: {}", kafkaPipelineId);

            // ── 응답 조립 ───────────────────────────────────────────
            GenerateResponse response = GenerateResponse.builder()
                    .pipelineId(kafkaPipelineId)
                    .query(request.getQuery())
                    .featureList(validatedState.getFeatureList())
                    .dbSchema(parseJson(validatedState.getDbSchema()))
                    .apiSpec(parseJson(validatedState.getApiSpec()))
                    .status(validatedState.getStatusMessage())
                    .retryCount(validatedState.getRetryCount())
                    .build();

            log.info("파이프라인 완료 | pipelineId: {}, retryCount: {}",
                    kafkaPipelineId, validatedState.getRetryCount());

            return ResponseEntity.ok(
                    ApiResponse.success(response, "파이프라인 생성 완료")
            );

        } catch (Exception e) {
            log.error("파이프라인 실패 | {}", e.getMessage(), e);
            throw new RuntimeException(e);

        } finally {
            MDC.remove("pipelineId");
        }
    }

    private JsonNode parseJson(String json) {
        try {
            if (json == null || json.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("JSON 파싱 실패, 빈 객체 반환 | {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }
}