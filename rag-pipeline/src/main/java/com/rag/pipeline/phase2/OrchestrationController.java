package com.rag.pipeline.phase2;

import com.rag.pipeline.common.response.ApiResponse;
import com.rag.pipeline.common.util.InMemoryMultipartFile;
import com.rag.pipeline.phase1.form.FormData;
import com.rag.pipeline.phase2.dto.GenerateResponse;
import com.rag.pipeline.phase2.graph.OrchestrationGraph;
import com.rag.pipeline.phase2.sse.PipelineProgressService;
import com.rag.pipeline.phase2.state.PipelineState;
import com.rag.pipeline.phase1.RagPipelineService;
import com.rag.pipeline.phase3.validation.ValidationService;
import com.rag.pipeline.phase3.retry.RetryService;
import com.rag.pipeline.phase4.kafka.KafkaProducerService;
import com.rag.pipeline.project.ProjectResponse;
import com.rag.pipeline.project.ProjectService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/orchestration")
@RequiredArgsConstructor
@Slf4j
public class OrchestrationController {

    private final RagPipelineService      ragPipelineService;
    private final OrchestrationGraph      orchestrationGraph;
    private final ValidationService       validationService;
    private final RetryService            retryService;
    private final KafkaProducerService    kafkaProducerService;
    private final ProjectService          projectService;
    private final PipelineProgressService progressService;
    private final ObjectMapper            objectMapper;

    @Qualifier("pipelineExecutor")
    private final Executor pipelineExecutor;

    /**
     * 파이프라인 시작 — 즉시 pipelineId 반환, 파이프라인은 백그라운드 실행.
     *
     * 클라이언트는 반환된 pipelineId로 GET /progress/{pipelineId} 구독.
     *
     * 요청 형식:
     *   POST /api/v1/orchestration/generate
     *   Content-Type: multipart/form-data
     *     - request: FormData JSON 문자열
     *     - files[]: PDF 파일 (선택, 최대 5개, 각 20MB)
     */
    @PostMapping(
        value    = "/generate",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ApiResponse<Map<String, String>> generate(
        @RequestPart("request") String requestJson,
        @RequestPart(value = "files", required = false) List<MultipartFile> files
    ) throws Exception {

        FormData formData = objectMapper.readValue(requestJson, FormData.class);
        validateFormData(formData);

        // 파일 유효성 검사 + 바이트 미리 읽기 (비동기 실행 전 요청 스레드에서 처리)
        List<MultipartFile> safeFiles = null;
        if (files != null && !files.isEmpty()) {
            safeFiles = files.stream().map(f -> {
                if (f.getSize() > 20 * 1024 * 1024)
                    throw new IllegalArgumentException(
                        "PDF 파일 크기 초과 (최대 20MB): " + f.getOriginalFilename());
                try {
                    return (MultipartFile) new InMemoryMultipartFile(
                        f.getOriginalFilename(), f.getBytes());
                } catch (Exception e) {
                    throw new RuntimeException("파일 읽기 실패: " + f.getOriginalFilename(), e);
                }
            }).collect(Collectors.toList());
        }

        String pipelineId = UUID.randomUUID().toString();
        log.info("파이프라인 시작 | pipelineId: {}, project: {}",
            pipelineId.substring(0, 8), formData.projectName());

        List<MultipartFile> finalFiles = safeFiles;
        pipelineExecutor.execute(() -> runPipeline(pipelineId, formData, finalFiles));

        return ApiResponse.ok(Map.of("pipelineId", pipelineId));
    }

    /**
     * SSE 구독 — 파이프라인 진행 상황을 실시간으로 수신.
     *
     * 이벤트 종류:
     *   progress  { step, message, percent, timestamp }
     *   complete  { result: GenerateResponse }
     *   error     { message }
     */
    @GetMapping(
        value    = "/progress/{pipelineId}",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter progress(@PathVariable String pipelineId) {
        log.debug("SSE 구독 | pipelineId: {}", pipelineId.substring(0, 8));
        return progressService.subscribe(pipelineId);
    }

    // ── 파이프라인 실행 (백그라운드 스레드) ──────────────────────────

    private void runPipeline(String pipelineId, FormData formData, List<MultipartFile> files) {
        MDC.put("pipelineId", pipelineId.substring(0, 8));
        try {
            // Phase 1
            progressService.send(pipelineId, "PHASE1_START",
                "PDF 파싱 및 검색 준비 중...", 5);
            PipelineState state = ragPipelineService.buildFromForm(formData, files);
            progressService.send(pipelineId, "PHASE1_DONE",
                "검색 컨텍스트 구성 완료", 25);

            // Phase 2
            PipelineState phase2State = orchestrationGraph.run(state, pipelineId);
            log.debug("Phase 2 완료 | retryCount: {}", phase2State.getRetryCount());

            // Phase 3
            progressService.send(pipelineId, "PHASE3", "결과 검증 중...", 85);
            PipelineState validatedState = validationService.validate(phase2State);
            if (!validatedState.isValidated()) {
                log.warn("Phase 3 검증 실패 — RetryService 진입");
                validatedState = retryService.retry(validatedState);
            }

            if (!validatedState.isValidated()) {
                log.warn("HITL 요청 | 자동 검증 실패");
                progressService.error(pipelineId, "자동 검증 실패 — 관리자 검토가 필요합니다");
                return;
            }

            // Phase 4
            progressService.send(pipelineId, "PHASE4", "결과 저장 중...", 95);
            String kafkaPipelineId = kafkaProducerService.publish(validatedState);
            log.info("파이프라인 완료 | pipelineId: {}, retryCount: {}",
                kafkaPipelineId, validatedState.getRetryCount());

            ProjectResponse savedProject = projectService.saveFromPipeline(validatedState, formData);
            GenerateResponse result = GenerateResponse.from(
                validatedState, savedProject.getId(), savedProject.getName());

            progressService.complete(pipelineId, result);

        } catch (Exception e) {
            log.error("파이프라인 실패 | {}", e.getMessage(), e);
            progressService.error(pipelineId, e.getMessage() != null ? e.getMessage() : "알 수 없는 오류");
        } finally {
            MDC.remove("pipelineId");
        }
    }

    // ── 유효성 검사 ──────────────────────────────────────────────────

    private void validateFormData(FormData form) {
        if (form.projectName() == null || form.projectName().isBlank())
            throw new IllegalArgumentException("프로젝트 이름은 필수입니다");
        if (form.problemDefinition() == null)
            throw new IllegalArgumentException("문제정의는 필수입니다");
        if (form.targetUsers() == null || form.targetUsers().isEmpty())
            throw new IllegalArgumentException("타겟유저는 최소 1명 필요합니다");
        if (form.featureDefinition() == null)
            throw new IllegalArgumentException("기능정의는 필수입니다");
    }
}
