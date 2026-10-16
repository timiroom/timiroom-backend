package com.timiroom.domain.pipeline.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.notification.enums.NotificationReferenceType;
import com.timiroom.domain.notification.service.NotificationService;
import com.timiroom.domain.notification.enums.NotificationType;
import com.timiroom.domain.pipeline.entity.ArtifactRevision;
import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import com.timiroom.domain.pipeline.entity.PipelineExecution;
import com.timiroom.domain.pipeline.repository.ArtifactRevisionRepository;
import com.timiroom.domain.pipeline.repository.PipelineArtifactRepository;
import com.timiroom.domain.pipeline.repository.PipelineExecutionRepository;
import com.timiroom.domain.requirement.entity.Requirement;
import com.timiroom.domain.requirement.service.RequirementService;
import com.timiroom.domain.requirement.enums.RequirementStatus;
import com.timiroom.infra.ragpipeline.RagPipelineClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.scheduler.Schedulers;

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
    private final ArtifactRevisionRepository revisionRepository;
    private final RequirementService requirementService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final com.timiroom.domain.spec.service.ArtifactWriteService artifactWriteService;
    private final com.timiroom.domain.spec.service.DocumentAccessService documentAccessService;
    private final com.timiroom.domain.usage.service.AiCallService aiCallService;

    /**
     * 파이프라인 시작
     * - requirement에서 FormData JSON 읽어 rag-pipeline 호출
     * - PipelineExecution 저장 (requirementId 연결)
     *
     * @param mode "collaborative" 또는 "sequential" — rag-pipeline 오케스트레이션 그래프 선택
     */
    @Transactional
    public Map<String, Object> startPipeline(
            Long memberId, Long requirementId, List<MultipartFile> files, String mode) throws Exception {

        Requirement requirement = requirementService.getById(requirementId);
        // 한도를 넘었으면 상태를 바꾸기 전에 막는다(429).
        var call = aiCallService.begin(memberId, requirement.getProjectId(),
                com.timiroom.domain.usage.enums.UsageFeature.PROJECT_GENERATION);
        requirementService.updateStatus(requirementId, RequirementStatus.PROCESSING);

        String pipelineId;
        try {
            pipelineId = ragPipelineClient.generate(requirement.getContent(), files, mode, call.headers());
        } catch (Exception e) {
            aiCallService.abandon(call);
            throw e;
        }

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
    public Map<String, Object> restartPipeline(Long memberId, String pipelineId, String mode) throws Exception {
        PipelineExecution prev = executionRepository.findByPipelineId(pipelineId)
                .orElseThrow(() -> new IllegalArgumentException("파이프라인을 찾을 수 없습니다: " + pipelineId));
        return startPipeline(memberId, prev.getRequirementId(), null, mode);
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
                                recordGenerationUsage(pipelineId, sse.data(), false);
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

            recordGenerationUsage(execution, objectMapper.valueToTree(result.get("usage")), true);

            int retryCount = result.containsKey("retryCount")
                    ? ((Number) result.get("retryCount")).intValue() : 0;
            execution.complete(retryCount);
            executionRepository.save(execution);

            saveArtifact(execution.getExecutionId(), PipelineArtifact.ArtifactType.PRD,             result.get("prdDocument"));
            saveArtifact(execution.getExecutionId(), PipelineArtifact.ArtifactType.DB_SCHEMA,       result.get("dbSchema"));
            saveArtifact(execution.getExecutionId(), PipelineArtifact.ArtifactType.API_SPEC,        result.get("apiSpec"));
            saveArtifact(execution.getExecutionId(), PipelineArtifact.ArtifactType.FEATURE_LIST,    result.get("featureList"));
            saveArtifact(execution.getExecutionId(), PipelineArtifact.ArtifactType.FEATURE_SPEC,    result.get("featureSpecDocument"));
            saveArtifact(execution.getExecutionId(), PipelineArtifact.ArtifactType.MARKET_RESEARCH, result.get("marketResearch"));

            // Requirement 상태 업데이트 + 알림 생성
            if (execution.getRequirementId() != null) {
                requirementService.updateStatus(execution.getRequirementId(), RequirementStatus.COMPLETED);
                Requirement req = requirementService.getById(execution.getRequirementId());
                notificationService.create(
                        execution.getMemberId(),
                        req.getProjectId(),
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
                        projectIdOf(execution.getRequirementId()),
                        NotificationType.PIPELINE_FAILED,
                        "파이프라인 실패",
                        errorMessage != null ? errorMessage : "알 수 없는 오류가 발생했습니다.",
                        NotificationReferenceType.PIPELINE,
                        execution.getExecutionId()
                );
            }
        });
    }

    /**
     * 생성 파이프라인 사용량 기록. 완료는 한도에 넣고, 실패는 기록만 남기고 한도에서 빼 준다.
     * 사용자 키 여부는 AI 서버가 줄마다 표시해 보낸다.
     */
    private void recordGenerationUsage(String pipelineId, String errorDataJson, boolean counted) {
        try {
            var data = objectMapper.readTree(errorDataJson);
            executionRepository.findByPipelineId(pipelineId)
                    .ifPresent(execution -> recordGenerationUsage(execution, data.path("usage"), counted));
        } catch (Exception e) {
            log.warn("생성 사용량을 읽지 못했습니다 | pipelineId: {}", pipelineId);
        }
    }

    private void recordGenerationUsage(PipelineExecution execution, com.fasterxml.jackson.databind.JsonNode usage, boolean counted) {
        // 진행 스트림을 다시 구독하면 완료·실패 이벤트가 또 올 수 있어, 이미 끝난 실행은 다시 기록하지 않는다.
        if (execution.getStatus() == PipelineExecution.ExecutionStatus.COMPLETED
                || execution.getStatus() == PipelineExecution.ExecutionStatus.FAILED) return;
        // 시작할 때 잡아 둔 예약은 결과가 따로 오므로 여기서 푼다.
        aiCallService.releaseGenerationHold(execution.getMemberId());
        var rows = aiCallService.parseNode(usage);
        if (rows.isEmpty()) return;
        var call = aiCallService.background(execution.getMemberId(), projectIdOf(execution.getRequirementId()),
                com.timiroom.domain.usage.enums.UsageFeature.PROJECT_GENERATION);
        aiCallService.finish(call, rows, "pipeline:" + execution.getPipelineId(), counted);
    }

    public List<PipelineExecution> getExecutionsByMember(Long memberId) {
        return executionRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }

    public List<PipelineArtifact> getArtifactsByExecution(Long executionId, Long memberId) {
        documentAccessService.requireRead(documentAccessService.projectIdForExecution(executionId), memberId);
        return artifactRepository.findByExecutionIdOrderByArtifactType(executionId);
    }

    /**
     * 문서 내용을 수정한다.
     *
     * 덮어쓰기 전에 이전 내용을 이력으로 남긴다. 이 이력이 있어야
     * "직전 대비 무엇이 바뀌었고 그 변경이 어디에 영향을 주는지"를 계산할 수 있다.
     */
    @Transactional
    public void updateArtifact(Long memberId, Long artifactId, String content) {
        artifactWriteService.writeCurrent(memberId, artifactId, content);
    }

    public List<PipelineArtifact> getLatestArtifactsByProject(Long projectId, Long memberId) {
        documentAccessService.requireRead(projectId, memberId);
        return getLatestArtifactsByProject(projectId);
    }

    public List<PipelineArtifact> getLatestArtifactsByProject(Long projectId) {
        List<Long> requirementIds = requirementService.getByProject(projectId).stream()
                .map(Requirement::getRequirementId)
                .toList();
        return executionRepository.findCurrentCompleted(requirementIds)
                .map(exec -> artifactRepository.findByExecutionIdOrderByArtifactType(exec.getExecutionId()))
                .orElse(List.of());
    }

    /** 실패 알림은 화면 이동용 정보일 뿐이므로 조회 실패가 실패 처리 자체를 막지 않게 한다. */
    private Long projectIdOf(Long requirementId) {
        try {
            return requirementService.getById(requirementId).getProjectId();
        } catch (RuntimeException e) {
            log.warn("알림용 projectId 조회 실패 | requirementId: {}", requirementId);
            return null;
        }
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
