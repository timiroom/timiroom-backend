package com.timiroom.domain.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.notification.NotificationReferenceType;
import com.timiroom.domain.notification.NotificationService;
import com.timiroom.domain.notification.NotificationType;
import com.timiroom.domain.requirement.Requirement;
import com.timiroom.domain.requirement.RequirementService;
import com.timiroom.domain.requirement.RequirementStatus;
import com.timiroom.infra.ragpipeline.RagPipelineClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineService {

    private final RagPipelineClient ragPipelineClient;
    private final PipelineExecutionRepository executionRepository;
    private final PipelineArtifactRepository artifactRepository;
    private final RequirementService requirementService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    /**
     * 파이프라인 시작
     * - requirement에서 FormData JSON 읽어 rag-pipeline 호출
     * - PipelineExecution 저장 (requirementId 연결)
     */
    @Transactional
    public Map<String, Object> startPipeline(
            Long memberId, Long requirementId, List<MultipartFile> files) throws Exception {

        Requirement requirement = requirementService.getById(requirementId);
        requirementService.updateStatus(requirementId, RequirementStatus.PROCESSING);

        String pipelineId = ragPipelineClient.generate(requirement.getContent(), files);

        PipelineExecution execution = PipelineExecution.builder()
                .pipelineId(pipelineId)
                .requirementId(requirementId)
                .memberId(memberId)
                .startedAt(LocalDateTime.now())
                .build();
        PipelineExecution saved = executionRepository.save(execution);

        log.info("파이프라인 시작 | executionId: {}, requirementId: {}, pipelineId: {}",
                saved.getExecutionId(), requirementId, pipelineId.substring(0, 8));
        return Map.of("executionId", saved.getExecutionId(), "pipelineId", pipelineId);
    }

    /**
     * 파이프라인 재시작: 기존 pipelineId로 실행 기록을 찾아 동일 requirementId로 재실행
     */
    @Transactional
    public Map<String, Object> restartPipeline(Long memberId, String pipelineId) throws Exception {
        PipelineExecution prev = executionRepository.findByPipelineId(pipelineId)
                .orElseThrow(() -> new IllegalArgumentException("파이프라인을 찾을 수 없습니다: " + pipelineId));
        return startPipeline(memberId, prev.getRequirementId(), null);
    }

    /**
     * SSE 구독: rag-pipeline 스트림 프록시 + 완료 시 Artifact 저장
     */
    public SseEmitter subscribeProgress(String pipelineId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        ragPipelineClient.getProgressFlux(pipelineId)
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                        sse -> {
                            String eventName = sse.event() != null ? sse.event() : "message";

                            // complete/error: 브라우저 연결 여부와 무관하게 artifact 저장 먼저 수행
                            if ("complete".equals(eventName)) {
                                handleComplete(pipelineId, sse.data());
                                sendToEmitter(emitter, eventName, sse.data());
                                completeEmitter(emitter);
                                return;
                            }
                            if ("error".equals(eventName)) {
                                handleError(pipelineId, extractErrorMessage(sse.data()));
                                sendToEmitter(emitter, eventName, sse.data());
                                completeEmitter(emitter);
                                return;
                            }

                            // progress 이벤트: 브라우저 끊김 시 무시 — rag-pipeline 구독은 유지
                            sendToEmitter(emitter, eventName, sse.data());
                        },
                        error -> {
                            log.error("SSE 오류 | pipelineId: {}", pipelineId, error);
                            handleError(pipelineId, error.getMessage());
                            completeEmitter(emitter);
                        },
                        () -> completeEmitter(emitter)
                );

        return emitter;
    }

    private void sendToEmitter(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            log.debug("브라우저 SSE 전송 실패 (연결 끊김) — rag-pipeline 구독 유지: {}", e.getMessage());
        }
    }

    private void completeEmitter(SseEmitter emitter) {
        try { emitter.complete(); } catch (Exception ignored) {}
    }

    /**
     * 파이프라인 완료 처리
     * - PipelineArtifact 저장
     * - PipelineExecution 상태 업데이트
     * - Requirement 상태 → COMPLETED
     * - Notification 생성
     */
    public void handleComplete(String pipelineId, String completeDataJson) {
        try {
            Map<String, Object> event = objectMapper.readValue(
                    completeDataJson, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) event.get("result");
            if (result == null) return;

            PipelineExecution execution = executionRepository.findByPipelineId(pipelineId)
                    .orElse(null);
            if (execution == null) {
                log.warn("PipelineExecution 없음 | pipelineId: {}", pipelineId);
                return;
            }

            int retryCount = result.containsKey("retryCount")
                    ? ((Number) result.get("retryCount")).intValue() : 0;
            execution.complete(retryCount);
            executionRepository.save(execution);

            saveArtifact(execution.getExecutionId(), PipelineArtifact.ArtifactType.PRD,             result.get("prdDocument"));
            saveArtifact(execution.getExecutionId(), PipelineArtifact.ArtifactType.DB_SCHEMA,       result.get("dbSchema"));
            saveArtifact(execution.getExecutionId(), PipelineArtifact.ArtifactType.API_SPEC,        result.get("apiSpec"));
            saveArtifact(execution.getExecutionId(), PipelineArtifact.ArtifactType.FEATURE_LIST,    result.get("featureList"));
            saveArtifact(execution.getExecutionId(), PipelineArtifact.ArtifactType.MARKET_RESEARCH, result.get("marketResearch"));

            // Requirement 상태 업데이트 + 알림 생성
            if (execution.getRequirementId() != null) {
                requirementService.updateStatus(execution.getRequirementId(), RequirementStatus.COMPLETED);
                Requirement req = requirementService.getById(execution.getRequirementId());
                notificationService.create(
                        execution.getMemberId(),
                        NotificationType.PIPELINE_COMPLETE,
                        "파이프라인 완료: " + req.getTitle(),
                        "PRD, DB 스키마, API 스펙 생성이 완료되었습니다.",
                        NotificationReferenceType.PIPELINE,
                        execution.getExecutionId()
                );
            }

            log.info("파이프라인 완료 저장 | executionId: {}", execution.getExecutionId());
        } catch (Exception e) {
            log.error("완료 처리 실패 | pipelineId: {}", pipelineId, e);
        }
    }

    /**
     * 파이프라인 실패 처리
     * - PipelineExecution 상태 → FAILED
     * - Requirement 상태 → FAILED
     * - Notification 생성
     */
    public void handleError(String pipelineId, String errorMessage) {
        executionRepository.findByPipelineId(pipelineId).ifPresent(execution -> {
            execution.fail(errorMessage);
            executionRepository.save(execution);

            if (execution.getRequirementId() != null) {
                requirementService.updateStatus(execution.getRequirementId(), RequirementStatus.FAILED);
                notificationService.create(
                        execution.getMemberId(),
                        NotificationType.PIPELINE_FAILED,
                        "파이프라인 실패",
                        errorMessage != null ? errorMessage : "알 수 없는 오류가 발생했습니다.",
                        NotificationReferenceType.PIPELINE,
                        execution.getExecutionId()
                );
            }
        });
    }

    public List<PipelineExecution> getExecutionsByMember(Long memberId) {
        return executionRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }

    public List<PipelineArtifact> getArtifactsByExecution(Long executionId) {
        return artifactRepository.findByExecutionIdOrderByArtifactType(executionId);
    }

    @Transactional
    public void updateArtifact(Long artifactId, String content) {
        PipelineArtifact artifact = artifactRepository.findById(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        artifact.updateContent(content);
        artifactRepository.save(artifact);
        log.info("Artifact 수정 | artifactId: {}", artifactId);
    }

    public List<PipelineArtifact> getLatestArtifactsByProject(Long projectId) {
        return requirementService.getByProject(projectId).stream()
                .flatMap(req -> executionRepository
                        .findByRequirementIdOrderByCreatedAtDesc(req.getRequirementId()).stream())
                .filter(exec -> exec.getStatus() == PipelineExecution.ExecutionStatus.COMPLETED)
                .findFirst()
                .map(exec -> artifactRepository.findByExecutionIdOrderByArtifactType(exec.getExecutionId()))
                .orElse(List.of());
    }

    private void saveArtifact(Long executionId, PipelineArtifact.ArtifactType type, Object content) {
        if (content == null) return;
        try {
            String json = (content instanceof String s)
                    ? s : objectMapper.writeValueAsString(content);
            if (json == null || json.isBlank() || json.equals("null")
                    || json.equals("{}") || json.equals("[]")) return;

            artifactRepository.save(PipelineArtifact.builder()
                    .executionId(executionId)
                    .artifactType(type)
                    .content(json)
                    .build());
        } catch (Exception e) {
            log.warn("Artifact 저장 실패 | type: {}, error: {}", type, e.getMessage());
        }
    }

    private String extractErrorMessage(String errorDataJson) {
        try {
            Map<String, Object> data = objectMapper.readValue(
                    errorDataJson, new TypeReference<>() {});
            Object msg = data.get("message");
            return msg != null ? msg.toString() : "알 수 없는 오류";
        } catch (Exception e) {
            return errorDataJson;
        }
    }
}
