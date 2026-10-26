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
import com.timiroom.domain.pipeline.entity.PipelineResultReceipt;
import com.timiroom.domain.pipeline.repository.PipelineResultReceiptRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final PlatformTransactionManager transactionManager;
    private final PipelineResultReceiptRepository receiptRepository;

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

        // 생성 결과가 프로젝트의 모든 문서를 바꾸므로 프로젝트 PM만 시작할 수 있다.
        Requirement requirement = requirementService.requireGeneration(requirementId, memberId);
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
        // 사용자가 진행 화면을 닫거나 나가도 결과가 저장되도록, 기록이 커밋되면 서버가 직접 결과를 받는다.
        String startedId = pipelineId;
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            watch(startedId);
                        }
                    });
        } else {
            watch(startedId);
        }
        return Map.of("executionId", saved.getExecutionId(), "pipelineId", pipelineId);
    }

    /**
     * 파이프라인 재시작: 기존 pipelineId로 실행 기록을 찾아 동일 requirementId로 재실행
     */
    @Transactional
    public Map<String, Object> restartPipeline(Long memberId, String pipelineId, String mode) throws Exception {
        PipelineExecution prev = executionRepository.findByPipelineId(pipelineId)
                .orElseThrow(() -> new IllegalArgumentException("파이프라인을 찾을 수 없습니다: " + pipelineId));
        if (memberId == null || !memberId.equals(prev.getMemberId())) {
            throw new IllegalArgumentException("파이프라인을 찾을 수 없습니다: " + pipelineId);
        }
        if (prev.getStatus() == PipelineExecution.ExecutionStatus.STORAGE_PENDING
                || (prev.getErrorMessage() != null && prev.getErrorMessage().startsWith("STORAGE_PENDING:"))) {
            throw new IllegalStateException("이미 Kafka에 발행된 결과입니다. 저장 상태를 확인한 후 재시작 여부를 결정해야 합니다.");
        }
        // 같은 요구사항으로 아직 생성 중인 실행이 있으면 두 번 돌리지 않는다(비용이 두 번 든다).
        boolean running = executionRepository.findByRequirementIdOrderByCreatedAtDesc(prev.getRequirementId()).stream()
                .anyMatch(e -> e.getStatus() == PipelineExecution.ExecutionStatus.RUNNING && !isStale(e));
        if (running) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "이미 생성 중입니다. 끝난 뒤에 다시 시도해 주세요.");
        return startPipeline(memberId, prev.getRequirementId(), null, mode);
    }

    /**
     * SSE 구독: rag-pipeline 스트림 프록시 + 완료 시 Artifact 저장
     */
    public SseEmitter subscribeProgress(String pipelineId, Long memberId) {
        requireProgressAccess(pipelineId, memberId);
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        // 결과 저장은 서버 쪽 감시(watch)가 맡는다. 브라우저가 떠나면 이 연결의 AI 서버 구독도 닫는다.
        reactor.core.Disposable subscription = ragPipelineClient.getProgressFlux(pipelineId)
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                        sse -> {
                            String eventName = sse.event() != null ? sse.event() : "message";

                            // complete/error: 서버 쪽 감시와 같은 처리를 한 번 더 시도한다(이미 처리됐으면 건너뛴다).
                            if ("complete".equals(eventName)) {
                                handleComplete(pipelineId, sse.data());
                                sendToEmitter(emitter, eventName, sse.data());
                                completeEmitter(emitter);
                                return;
                            }
                            if ("error".equals(eventName)) {
                                handleErrorEvent(pipelineId, sse.data());
                                sendToEmitter(emitter, eventName, sse.data());
                                completeEmitter(emitter);
                                return;
                            }

                            // progress 이벤트: 브라우저 끊김 시 무시 — rag-pipeline 구독은 유지
                            sendToEmitter(emitter, eventName, sse.data());
                        },
                        error -> {
                            // AI 서버와의 연결이 끊긴 것이지 생성이 실패한 것은 아니다. 결과는 서버 쪽 감시가 받는다.
                            log.warn("진행 스트림 연결 오류 | pipelineId: {}, error: {}", pipelineId, error.getMessage());
                            completeEmitter(emitter);
                        },
                        () -> completeEmitter(emitter)
                );
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        emitter.onError(error -> subscription.dispose());

        return emitter;
    }

    /** 생성을 시작한 사람이거나 그 프로젝트의 워크스페이스 멤버만 진행 상황을 본다. */
    private void requireProgressAccess(String pipelineId, Long memberId) {
        if (memberId == null) throw new SecurityException("ACCESS_DENIED");
        PipelineExecution execution = executionRepository.findByPipelineId(pipelineId)
                .orElseThrow(() -> new SecurityException("ACCESS_DENIED"));
        if (memberId.equals(execution.getMemberId())) return;
        documentAccessService.requireRead(documentAccessService.projectIdForExecution(execution.getExecutionId()), memberId);
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
            boolean accepted = Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(tx -> {
                PipelineExecution execution = executionRepository.findByPipelineIdForUpdate(pipelineId).orElse(null);
                if (execution == null || execution.getStatus() == PipelineExecution.ExecutionStatus.COMPLETED
                        || execution.getStatus() == PipelineExecution.ExecutionStatus.FAILED) return false;
                PipelineResultReceipt receipt = receiptRepository.findById(pipelineId).orElse(null);
                if (receipt == null) receipt = new PipelineResultReceipt(pipelineId, completeDataJson);
                else {
                    requireSameArtifacts(receipt.getPayload(), completeDataJson);
                    receipt.updatePayload(completeDataJson);
                }
                receiptRepository.save(receipt);
                execution.waitForStorage();
                executionRepository.save(execution);
                return true;
            }));
            if (!accepted) return;
            if (result.get("storage") instanceof Map<?, ?> storage && !"stored".equals(storage.get("status"))) {
                return;
            }

            int retryCount = result.containsKey("retryCount")
                    ? ((Number) result.get("retryCount")).intValue() : 0;
            PipelineExecution execution = new TransactionTemplate(transactionManager).execute(tx -> {
                PipelineExecution current = executionRepository.findByPipelineIdForUpdate(pipelineId).orElseThrow();
                if (current.getStatus() == PipelineExecution.ExecutionStatus.COMPLETED) return null;
                if (current.getStatus() != PipelineExecution.ExecutionStatus.STORAGE_PENDING) return null;
                PipelineResultReceipt receipt = receiptRepository.findById(pipelineId).orElseThrow();
                requireSameArtifacts(receipt.getPayload(), completeDataJson);
                writeCompletedArtifacts(current, result);
                current.complete(retryCount);
                receipt.updatePayload(completeDataJson);
                receipt.complete();
                receiptRepository.save(receipt);
                executionRepository.saveAndFlush(current);
                return current;
            });
            if (execution != null) {
                try { recordGenerationUsage(execution, objectMapper.valueToTree(result.get("usage")), true); }
                catch (RuntimeException usageError) { log.error("완료 후 사용량 기록 실패 | pipelineId: {}", pipelineId, usageError); }
            }
        } catch (Exception e) {
            log.error("완료 처리 실패 | pipelineId: {}", pipelineId, e);
            throw new IllegalStateException("생성 결과 저장을 완료하지 못했습니다. 저장 확인 작업에서 재시도합니다.", e);
        }
    }

    private void writeCompletedArtifacts(PipelineExecution execution, Map<String, Object> result) {
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
                        "문서 생성 완료: " + req.getTitle(),
                        "PRD, 기능 명세, API 명세, ERD를 만들었습니다.",
                        NotificationReferenceType.PIPELINE,
                        execution.getExecutionId()
                );
            }

            log.info("파이프라인 완료 저장 | executionId: {}", execution.getExecutionId());
    }

    private void requireSameArtifacts(String existing, String incoming) {
        try {
            var before = objectMapper.readTree(existing).path("result");
            var after = objectMapper.readTree(incoming).path("result");
            for (String key : List.of("prdDocument", "dbSchema", "apiSpec", "featureList", "featureSpecDocument", "marketResearch"))
                if (!before.path(key).equals(after.path(key))) throw new IllegalStateException("동일 pipelineId의 산출물이 변경됐습니다: " + key);
        } catch (java.io.IOException e) { throw new IllegalStateException("저장 결과 형식 오류", e); }
    }

    /**
     * 파이프라인 실패 처리
     * - PipelineExecution 상태 → FAILED
     * - Requirement 상태 → FAILED
     * - Notification 생성
     */
    public void handleError(String pipelineId, String errorMessage) {
        handleError(pipelineId, errorMessage, null);
    }

    /** AI 서버의 error 이벤트(본문에 실패 전까지 쓴 사용량이 들어 있다) */
    public void handleErrorEvent(String pipelineId, String errorDataJson) {
        try {
            var event = objectMapper.readTree(errorDataJson);
            if (event.path("storage").path("kafkaPublished").asBoolean(false)
                    && !"stored".equals(event.path("storage").path("status").asText())) {
                var result = event.path("partialResult");
                if (!result.isObject()) throw new IllegalStateException("저장 대기 결과가 없습니다");
                var pending = result.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) pending).set("storage", event.path("storage"));
                handleComplete(pipelineId, objectMapper.writeValueAsString(Map.of("result", pending)));
                return;
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("저장 확인 이벤트 형식 오류", e); }
        com.fasterxml.jackson.databind.JsonNode usage = null;
        try {
            usage = objectMapper.readTree(errorDataJson).path("usage");
        } catch (Exception e) {
            log.debug("실패 이벤트 사용량 없음 | pipelineId: {}", pipelineId);
        }
        String message = extractErrorMessage(errorDataJson);
        // AI 서버가 진행 소식을 30분 받지 못해 보낸 타임아웃(서버 재시작 등)은 사용자가 이해할 문장으로 바꾼다.
        if ("타임아웃".equals(message)) message = "생성이 중간에 멈췄습니다. 다시 시도해 주세요.";
        handleError(pipelineId, message, usage);
    }

    private void handleError(String pipelineId, String errorMessage, com.fasterxml.jackson.databind.JsonNode usage) {
        if (executionRepository.finishIfRunning(pipelineId, PipelineExecution.ExecutionStatus.FAILED, 0, truncate(errorMessage)) == 0) {
            log.debug("이미 처리된 실행 | pipelineId: {}", pipelineId);
            return;
        }
        executionRepository.findByPipelineId(pipelineId).ifPresent(execution -> {
            // 실패한 생성은 기록만 남기고 한도에서 빼 준다.
            recordGenerationUsage(execution, usage, false);

            if (execution.getRequirementId() != null) {
                requirementService.updateStatus(execution.getRequirementId(), RequirementStatus.FAILED);
                String title = titleOf(execution.getRequirementId());
                notificationService.create(
                        execution.getMemberId(),
                        projectIdOf(execution.getRequirementId()),
                        NotificationType.PIPELINE_FAILED,
                        title == null ? "문서 생성 실패" : "문서 생성 실패: " + title,
                        friendlyFailure(errorMessage),
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
    /** 끝난 생성의 사용량 기록. 결과 처리를 맡은 한 곳에서만 부른다(finishIfRunning). */
    private void recordGenerationUsage(PipelineExecution execution, com.fasterxml.jackson.databind.JsonNode usage, boolean counted) {
        // 시작할 때 잡아 둔 예약은 결과가 따로 오므로 여기서 푼다.
        aiCallService.releaseGenerationHold(execution.getMemberId());
        var rows = aiCallService.parseNode(usage);
        if (rows.isEmpty()) return;
        var call = aiCallService.background(execution.getMemberId(), projectIdOf(execution.getRequirementId()),
                com.timiroom.domain.usage.enums.UsageFeature.PROJECT_GENERATION);
        aiCallService.finish(call, rows, "pipeline:" + execution.getPipelineId(), counted);
    }

    // ── 서버 쪽 감시와 복구 ─────────────────────────────────────────

    /** 결과를 기다릴 최대 시간. AI 서버는 끝난 결과를 1시간 보관하고, 진행이 30분 멈추면 타임아웃을 보낸다. */
    static final java.time.Duration STALE_AFTER = java.time.Duration.ofMinutes(70);

    private final java.util.Set<String> watching = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 브라우저와 상관없이 이 서버가 AI 서버의 결과를 받아 저장한다. 같은 생성은 서버마다 한 번만 지켜본다.
     * 연결이 끊기면 그냥 멈추고, 복구 작업(recoverRunning)이 다시 붙거나 너무 오래되면 실패로 정리한다.
     */
    public void watch(String pipelineId) {
        if (pipelineId == null || !watching.add(pipelineId)) return;
        try {
            ragPipelineClient.getProgressFlux(pipelineId)
                    .publishOn(Schedulers.boundedElastic())
                    .doFinally(signal -> watching.remove(pipelineId))
                    .subscribe(sse -> {
                        String eventName = sse.event() != null ? sse.event() : "message";
                        if ("complete".equals(eventName)) handleComplete(pipelineId, sse.data());
                        else if ("error".equals(eventName)) handleErrorEvent(pipelineId, sse.data());
                    }, error -> log.warn("생성 감시 연결 끊김 | pipelineId: {}, error: {}", pipelineId, error.getMessage()));
        } catch (RuntimeException e) {
            watching.remove(pipelineId);
            log.warn("생성 감시 시작 실패 | pipelineId: {}", pipelineId, e);
        }
    }

    /**
     * 진행 중으로 남은 생성 정리(서버 재시작·배포 뒤 등). 아직 기다릴 만하면 다시 지켜보고,
     * 너무 오래됐으면 실패로 바꿔 사용자에게 알리고 다시 시도할 수 있게 한다(사용량은 한도에서 빼 준다).
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${pipeline.recover-delay-ms:30000}", initialDelay = 10_000)
    public void recoverRunning() {
        try {
            for (PipelineExecution execution : executionRepository.findByStatus(PipelineExecution.ExecutionStatus.RUNNING)) {
                if (isStale(execution)) {
                    log.warn("오래 멈춘 생성을 실패로 정리 | pipelineId: {}", execution.getPipelineId());
                    handleError(execution.getPipelineId(), "생성이 중간에 멈췄습니다. 다시 시도해 주세요.", null);
                } else {
                    watch(execution.getPipelineId());
                }
            }
        } catch (RuntimeException e) {
            log.warn("진행 중 생성 복구 실패: {}", e.getMessage());
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${pipeline.storage-recover-delay-ms:5000}", initialDelay = 5000)
    public void recoverPendingResults() {
        for (PipelineResultReceipt receipt : receiptRepository.findTop100ByCompletedFalseOrderByCreatedAtAsc()) {
            try {
                recoverReceipt(receipt);
            } catch (Exception e) {
                log.warn("저장 확인 복구 대기 | pipelineId: {}, type: {}", receipt.getPipelineId(), e.getClass().getSimpleName());
            }
        }
    }

    private void recoverReceipt(PipelineResultReceipt receipt) throws java.io.IOException {
        var event = objectMapper.readTree(receipt.getPayload());
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) event.path("result");
        Map<String, Object> storage = "stored".equals(result.path("storage").path("status").asText())
                ? Map.of("status", "stored", "kafkaPublished", true) : ragPipelineClient.storageStatus(receipt.getPipelineId());
        if (!"stored".equals(storage.get("status"))) return;
        result.set("storage", objectMapper.valueToTree(storage));
        result.put("completionStatus", "STORED");
        handleComplete(receipt.getPipelineId(), objectMapper.writeValueAsString(event));
    }

    public Map<String, Object> executionState(Long memberId, String pipelineId) {
        PipelineExecution execution = executionRepository.findByPipelineId(pipelineId)
                .filter(value -> memberId != null && memberId.equals(value.getMemberId()))
                .orElseThrow(() -> new SecurityException("파이프라인을 조회할 수 없습니다"));
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (execution.getStatus() == PipelineExecution.ExecutionStatus.RUNNING) {
            try {
                Map<String, Object> remote = ragPipelineClient.progressState(pipelineId);
                String remoteStatus = String.valueOf(remote.get("status"));
                if ("COMPLETED".equals(remoteStatus) && remote.get("result") instanceof Map<?, ?>) {
                    handleComplete(pipelineId, objectMapper.writeValueAsString(Map.of("result", remote.get("result"))));
                } else if ("FAILED".equals(remoteStatus) && remote.get("error") instanceof Map<?, ?>) {
                    handleErrorEvent(pipelineId, objectMapper.writeValueAsString(remote.get("error")));
                } else if ("RUNNING".equals(remoteStatus)) {
                    if (remote.get("lastProgress") != null) result.put("lastProgress", remote.get("lastProgress"));
                    watch(pipelineId);
                } else {
                    result.put("connectionStatus", "UNAVAILABLE");
                }
                execution = executionRepository.findByPipelineId(pipelineId).orElse(execution);
            } catch (Exception e) {
                // A lost connection/another replica is not proof that generation failed.
                result.put("connectionStatus", "UNAVAILABLE");
                log.debug("진행 상태 확인 대기 | pipelineId: {}, type: {}", pipelineId, e.getClass().getSimpleName());
            }
        }
        if (execution.getStatus() == PipelineExecution.ExecutionStatus.STORAGE_PENDING) {
            try {
                var receipt = receiptRepository.findById(pipelineId);
                if (receipt.isPresent()) recoverReceipt(receipt.get());
                execution = executionRepository.findByPipelineId(pipelineId).orElse(execution);
            } catch (Exception e) {
                result.put("connectionStatus", "UNAVAILABLE");
                log.debug("저장 상태 확인 대기 | pipelineId: {}, type: {}", pipelineId, e.getClass().getSimpleName());
            }
        }
        result.put("pipelineId", pipelineId);
        result.put("status", execution.getStatus().name());
        result.put("retrySafe", execution.getStatus() == PipelineExecution.ExecutionStatus.FAILED);
        if (execution.getStatus() == PipelineExecution.ExecutionStatus.FAILED) {
            result.put("message", friendlyFailure(execution.getErrorMessage()));
        }
        if (execution.getStatus() == PipelineExecution.ExecutionStatus.STORAGE_PENDING) {
            result.put("message", "결과 발행은 완료됐으며 DB 저장을 확인 중입니다. 다시 생성하지 마세요.");
        }
        if (execution.getStatus() == PipelineExecution.ExecutionStatus.COMPLETED) {
            receiptRepository.findById(pipelineId).ifPresent(receipt -> {
                try { result.put("result", objectMapper.readTree(receipt.getPayload()).path("result")); }
                catch (java.io.IOException e) { throw new IllegalStateException(e); }
            });
            if (!result.containsKey("result")) {
                Map<String, Object> legacy = new java.util.LinkedHashMap<>();
                Map<String, String> keys = Map.of("PRD", "prdDocument", "DB_SCHEMA", "dbSchema",
                        "API_SPEC", "apiSpec", "FEATURE_LIST", "featureList", "MARKET_RESEARCH", "marketResearch");
                for (PipelineArtifact artifact : artifactRepository.findByExecutionIdOrderByArtifactType(execution.getExecutionId())) {
                    String key = keys.get(artifact.getArtifactType().name());
                    if (key == null) continue;
                    try { legacy.put(key, objectMapper.readTree(artifact.getContent())); }
                    catch (Exception ignored) { legacy.put(key, artifact.getContent()); }
                }
                if (!legacy.isEmpty()) result.put("result", legacy);
            }
        }
        return result;
    }

    /** 내 진행 중인 생성(새로고침하거나 다시 들어와도 진행 화면을 이어 보이게) */
    public List<Map<String, Object>> activeRuns(Long memberId) {
        return java.util.stream.Stream.concat(
                executionRepository.findByMemberIdAndStatus(memberId, PipelineExecution.ExecutionStatus.RUNNING).stream(),
                executionRepository.findByMemberIdAndStatus(memberId, PipelineExecution.ExecutionStatus.STORAGE_PENDING).stream())
                .filter(e -> e.getStatus() == PipelineExecution.ExecutionStatus.STORAGE_PENDING || !isStale(e))
                .map(e -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("pipelineId", e.getPipelineId());
                    row.put("projectId", projectIdOf(e.getRequirementId()));
                    row.put("startedAt", e.getStartedAt());
                    row.put("status", e.getStatus().name());
                    return row;
                })
                .toList();
    }

    static boolean isStale(PipelineExecution execution) {
        LocalDateTime started = execution.getStartedAt() != null ? execution.getStartedAt() : execution.getCreatedAt();
        return started != null && started.isBefore(LocalDateTime.now().minus(STALE_AFTER));
    }

    private static String truncate(String message) {
        if (message == null) return null;
        return message.length() > 1000 ? message.substring(0, 1000) : message;
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

    private String titleOf(Long requirementId) {
        try {
            return requirementService.getById(requirementId).getTitle();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** AI 서버의 내부 사유를 사용자가 다음에 할 일을 알 수 있는 문장으로 바꾼다. */
    static String friendlyFailure(String message) {
        if (message == null || message.isBlank()) return "알 수 없는 문제로 생성하지 못했습니다. 다시 시도해 주세요.";
        if (message.contains("관리자 검토")) return "만든 문서가 자동 검증을 통과하지 못했습니다. 요구사항을 조금 더 구체적으로 적어 다시 생성해 주세요.";
        return message;
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
            throw new IllegalStateException("Artifact 저장 실패: " + type, e);
        }
    }

    private String extractErrorMessage(String errorDataJson) {
        try {
            Map<String, Object> data = objectMapper.readValue(
                    errorDataJson, new TypeReference<>() {});
            Object msg = data.get("message");
            if (data.get("storage") instanceof Map<?, ?> storage
                    && Boolean.TRUE.equals(storage.get("kafkaPublished")) && !"stored".equals(storage.get("status"))) {
                return "STORAGE_PENDING: Kafka 발행 후 DB 저장 확인 대기. 중복 생성하지 마세요.";
            }
            if ("FEATURE_SPEC".equals(data.get("failedStage"))) {
                return "기능명세 생성 계약 검증 실패. API·DB 설계는 실행되지 않았습니다."
                        + (data.get("validationMessage") instanceof String detail ? " " + detail : "");
            }
            return msg != null ? msg.toString() : "알 수 없는 오류";
        } catch (Exception e) {
            return errorDataJson;
        }
    }
}
