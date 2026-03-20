package com.rag.pipeline.phase2;

import com.rag.pipeline.phase1.RagPipelineService;
import com.rag.pipeline.phase2.graph.OrchestrationGraph;
import com.rag.pipeline.phase2.state.PipelineState;
import com.rag.pipeline.phase3.retry.RetryService;
import com.rag.pipeline.phase3.validation.ValidationService;
import com.rag.pipeline.phase4.kafka.KafkaProducerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 전체 파이프라인 REST API
 *
 * POST /api/v1/orchestration/generate
 *   Phase 1 RAG → Phase 2 멀티 에이전트 → Phase 3 검증 → Phase 4 Kafka 발행
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/orchestration")
@RequiredArgsConstructor
public class OrchestrationController {

    private final RagPipelineService  ragPipelineService;
    private final OrchestrationGraph  orchestrationGraph;
    private final ValidationService   validationService;
    private final RetryService        retryService;
    private final KafkaProducerService kafkaProducerService;

    /**
     * 전체 파이프라인 실행 (Phase 1 + 2 + 3 + 4)
     *
     * POST /api/v1/orchestration/generate
     * { "query": "로그인 기능 만들어줘" }
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(
            @RequestBody GenerateRequest request) {

        log.info("=== 전체 파이프라인 시작 === query: '{}'", request.query());

        // Phase 1 — RAG 컨텍스트 생성
        String contextPrompt = ragPipelineService.buildContext(request.query());

        // Phase 2 — 멀티 에이전트 오케스트레이션
        PipelineState phase2Result = orchestrationGraph.execute(
                request.query(),
                contextPrompt
        );

        // Phase 3 — 검증
        PipelineState validatedResult = validationService.validate(phase2Result);

        // 검증 실패 시 재시도
        if (!validatedResult.isValidated()) {
            log.warn("검증 실패 — 재시도 시작");
            validatedResult = retryService.retry(validatedResult);
        }

        // Human-in-the-Loop 필요 여부 확인
        if (!validatedResult.isValidated()) {
            return ResponseEntity.status(202).body(Map.of(
                    "status",  "HUMAN_REVIEW_REQUIRED",
                    "message", "자동 검증 실패 — 관리자 검토가 필요합니다",
                    "query",   request.query()
            ));
        }

        // Phase 4 — Kafka 발행 (비동기)
        String pipelineId = kafkaProducerService.publish(validatedResult);

        log.info("=== 전체 파이프라인 완료 === pipelineId: {}", pipelineId);

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();

            Object dbSchemaObj = mapper.readValue(validatedResult.getDbSchema(), Object.class);
            Object apiSpecObj  = mapper.readValue(validatedResult.getApiSpec(),  Object.class);

            return ResponseEntity.ok(Map.of(
                    "pipelineId",  pipelineId,
                    "query",       request.query(),
                    "featureList", validatedResult.getFeatureList(),
                    "dbSchema",    dbSchemaObj,
                    "apiSpec",     apiSpecObj,
                    "status",      validatedResult.getStatusMessage(),
                    "retryCount",  validatedResult.getRetryCount()
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "pipelineId",  pipelineId,
                    "query",       request.query(),
                    "featureList", validatedResult.getFeatureList(),
                    "dbSchema",    validatedResult.getDbSchema(),
                    "apiSpec",     validatedResult.getApiSpec(),
                    "status",      validatedResult.getStatusMessage(),
                    "retryCount",  validatedResult.getRetryCount()
            ));
        }
    }

    record GenerateRequest(String query) {}
}