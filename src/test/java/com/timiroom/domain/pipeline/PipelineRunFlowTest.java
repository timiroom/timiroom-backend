package com.timiroom.domain.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.notification.service.NotificationService;
import com.timiroom.domain.pipeline.entity.PipelineExecution;
import com.timiroom.domain.pipeline.repository.ArtifactRevisionRepository;
import com.timiroom.domain.pipeline.repository.PipelineArtifactRepository;
import com.timiroom.domain.pipeline.repository.PipelineExecutionRepository;
import com.timiroom.domain.pipeline.service.PipelineService;
import com.timiroom.domain.requirement.entity.Requirement;
import com.timiroom.domain.requirement.service.RequirementService;
import com.timiroom.domain.spec.service.ArtifactWriteService;
import com.timiroom.domain.spec.service.DocumentAccessService;
import com.timiroom.domain.usage.service.AiCallService;
import com.timiroom.infra.ragpipeline.RagPipelineClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 생성 도중 이탈·중복 수신·재시작 흐름 */
class PipelineRunFlowTest {

    private final PipelineExecutionRepository executions = mock(PipelineExecutionRepository.class);
    private final PipelineArtifactRepository artifacts = mock(PipelineArtifactRepository.class);
    private final RequirementService requirements = mock(RequirementService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final RagPipelineClient rag = mock(RagPipelineClient.class);
    private final com.timiroom.domain.pipeline.repository.PipelineResultReceiptRepository receipts = mock(com.timiroom.domain.pipeline.repository.PipelineResultReceiptRepository.class);
    private PipelineService service;

    private static final String COMPLETE = """
            {"result":{"prdDocument":{"title":"PRD"},"dbSchema":{"tables":[]},"apiSpec":{"endpoints":[]},"featureList":[],"usage":[]}}
            """;

    @BeforeEach
    void setUp() {
        service = new PipelineService(rag, executions, artifacts, mock(ArtifactRevisionRepository.class), requirements,
                notifications, new ObjectMapper(), mock(ArtifactWriteService.class), mock(DocumentAccessService.class),
                mock(AiCallService.class), mock(org.springframework.transaction.PlatformTransactionManager.class), receipts);
        java.util.Map<String, com.timiroom.domain.pipeline.entity.PipelineResultReceipt> receiptRows = new java.util.HashMap<>();
        when(receipts.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(receiptRows.get(inv.getArgument(0))));
        when(receipts.save(any())).thenAnswer(inv -> { var row = (com.timiroom.domain.pipeline.entity.PipelineResultReceipt) inv.getArgument(0); receiptRows.put(row.getPipelineId(), row); return row; });
        when(requirements.getById(30L)).thenReturn(Requirement.builder().requirementId(30L).projectId(5L).title("프로젝트").build());
        when(artifacts.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private PipelineExecution execution(String id, long member, PipelineExecution.ExecutionStatus status, LocalDateTime startedAt) {
        PipelineExecution e = PipelineExecution.builder().executionId(9L).requirementId(30L).memberId(member)
                .pipelineId(id).startedAt(startedAt).build();
        if (status == PipelineExecution.ExecutionStatus.FAILED) e.fail("실패");
        if (status == PipelineExecution.ExecutionStatus.COMPLETED) e.complete(0);
        when(executions.findByPipelineId(id)).thenReturn(Optional.of(e));
        when(executions.findByPipelineIdForUpdate(id)).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    void 완료를_두_번_받아도_문서와_알림은_한_번만_남긴다() {
        execution("p-1", 8L, PipelineExecution.ExecutionStatus.RUNNING, LocalDateTime.now());
        when(executions.finishIfRunning(eq("p-1"), eq(PipelineExecution.ExecutionStatus.COMPLETED), anyInt(), any()))
                .thenReturn(1).thenReturn(0);

        service.handleComplete("p-1", COMPLETE);
        service.handleComplete("p-1", COMPLETE);

        verify(artifacts, times(3)).save(any());
        verify(notifications, times(1)).create(eq(8L), eq(5L), any(), any(), any(), any(), eq(9L));
    }

    @Test
    void 완료된_뒤에_온_실패는_무시한다() {
        execution("p-2", 8L, PipelineExecution.ExecutionStatus.COMPLETED, LocalDateTime.now());
        when(executions.finishIfRunning(eq("p-2"), any(), anyInt(), any())).thenReturn(0);

        service.handleErrorEvent("p-2", "{\"message\":\"타임아웃\"}");

        verifyNoInteractions(notifications);
    }

    @Test
    void 오래_멈춘_생성은_실패로_정리하고_진행_중인_생성은_서버가_다시_지켜본다() {
        PipelineExecution stale = execution("p-old", 8L, PipelineExecution.ExecutionStatus.RUNNING, LocalDateTime.now().minusHours(2));
        PipelineExecution fresh = execution("p-new", 8L, PipelineExecution.ExecutionStatus.RUNNING, LocalDateTime.now().minusMinutes(5));
        when(executions.findByStatus(PipelineExecution.ExecutionStatus.RUNNING)).thenReturn(List.of(stale, fresh));
        when(executions.finishIfRunning(eq("p-old"), eq(PipelineExecution.ExecutionStatus.FAILED), anyInt(), any())).thenReturn(1);
        when(rag.getProgressFlux("p-new")).thenReturn(Flux.never());

        service.recoverRunning();

        verify(executions).finishIfRunning(eq("p-old"), eq(PipelineExecution.ExecutionStatus.FAILED), anyInt(),
                contains("멈췄습니다"));
        verify(rag).getProgressFlux("p-new");
        verify(rag, never()).getProgressFlux("p-old");
    }

    @Test
    void 같은_생성은_서버가_한_번만_지켜본다() {
        when(rag.getProgressFlux("p-3")).thenReturn(Flux.never());

        service.watch("p-3");
        service.watch("p-3");

        verify(rag, times(1)).getProgressFlux("p-3");
    }

    @Test
    void 다른_사람의_생성은_다시_시작할_수_없다() {
        execution("p-4", 8L, PipelineExecution.ExecutionStatus.FAILED, LocalDateTime.now());

        assertThatThrownBy(() -> service.restartPipeline(99L, "p-4", "collaborative"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 아직_생성_중이면_다시_시작하지_않는다() {
        PipelineExecution running = execution("p-5", 8L, PipelineExecution.ExecutionStatus.RUNNING, LocalDateTime.now());
        when(executions.findByRequirementIdOrderByCreatedAtDesc(30L)).thenReturn(List.of(running));

        assertThatThrownBy(() -> service.restartPipeline(8L, "p-5", "collaborative"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("이미 생성 중");
    }

    @Test
    void 내_진행_중인_생성을_프로젝트와_함께_돌려준다() {
        PipelineExecution running = execution("p-6", 8L, PipelineExecution.ExecutionStatus.RUNNING, LocalDateTime.now());
        when(executions.findByMemberIdAndStatus(8L, PipelineExecution.ExecutionStatus.RUNNING)).thenReturn(List.of(running));

        var runs = service.activeRuns(8L);

        assertThat(runs).hasSize(1);
        assertThat(runs.get(0)).containsEntry("pipelineId", "p-6").containsEntry("projectId", 5L);
    }

    @Test
    void 저장_확인_대기는_완료로_기록하지_않는다() {
        PipelineExecution pending = execution("pending", 8L, PipelineExecution.ExecutionStatus.RUNNING, LocalDateTime.now());
        service.handleComplete("pending", "{\"result\":{\"storage\":{\"status\":\"pending\",\"kafkaPublished\":true}}}");
        verify(executions, never()).finishIfRunning(anyString(), eq(PipelineExecution.ExecutionStatus.COMPLETED), anyInt(), any());
        assertThat(pending.getStatus()).isEqualTo(PipelineExecution.ExecutionStatus.STORAGE_PENDING);
        verifyNoInteractions(artifacts);
    }

    @Test
    void 저장_대기_실행은_다시_생성하지_않는다() {
        PipelineExecution pending = execution("pending", 8L, PipelineExecution.ExecutionStatus.FAILED, LocalDateTime.now());
        pending.fail("STORAGE_PENDING: pending verification");
        assertThatThrownBy(() -> service.restartPipeline(8L, "pending", "collaborative"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Kafka");
    }

    @Test
    void 상위_생성_실패를_하위_DB_실패로_기록하지_않는다() {
        service.handleErrorEvent("feature-failed", "{\"message\":\"관리자 검토\",\"failedStage\":\"FEATURE_SPEC\",\"validationMessage\":\"FEATURE_SPEC_JSON\"}");
        verify(executions).finishIfRunning(eq("feature-failed"), eq(PipelineExecution.ExecutionStatus.FAILED), eq(0),
                contains("API·DB 설계는 실행되지 않았습니다"));
    }
}
