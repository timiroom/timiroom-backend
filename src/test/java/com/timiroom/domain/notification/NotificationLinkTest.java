package com.timiroom.domain.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.notification.controller.NotificationController;
import com.timiroom.domain.notification.enums.NotificationReferenceType;
import com.timiroom.domain.notification.enums.NotificationType;
import com.timiroom.domain.notification.service.NotificationService;
import com.timiroom.domain.notification.service.NotificationService.NotificationNotFoundException;
import com.timiroom.domain.pipeline.entity.PipelineExecution;
import com.timiroom.domain.pipeline.repository.ArtifactRevisionRepository;
import com.timiroom.domain.pipeline.repository.PipelineArtifactRepository;
import com.timiroom.domain.pipeline.repository.PipelineExecutionRepository;
import com.timiroom.domain.pipeline.service.PipelineService;
import com.timiroom.domain.requirement.entity.Requirement;
import com.timiroom.domain.requirement.service.RequirementService;
import com.timiroom.domain.spec.service.ArtifactWriteService;
import com.timiroom.domain.spec.service.DocumentAccessService;
import com.timiroom.infra.ragpipeline.RagPipelineClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class NotificationLinkTest {

    @Test
    void 파이프라인_실패_알림에_projectId를_담는다() {
        var executions = mock(PipelineExecutionRepository.class);
        var requirements = mock(RequirementService.class);
        var notifications = mock(NotificationService.class);
        var pipeline = new PipelineService(mock(RagPipelineClient.class), executions, mock(PipelineArtifactRepository.class),
                mock(ArtifactRevisionRepository.class), requirements, notifications, new ObjectMapper(),
                mock(ArtifactWriteService.class), mock(DocumentAccessService.class), mock(com.timiroom.domain.usage.service.AiCallService.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class), mock(com.timiroom.domain.pipeline.repository.PipelineResultReceiptRepository.class));
        var execution = PipelineExecution.builder().executionId(9L).requirementId(30L).memberId(8L).pipelineId("p-1").build();
        when(executions.findByPipelineId("p-1")).thenReturn(Optional.of(execution));
        when(executions.finishIfRunning(eq("p-1"), any(), anyInt(), any())).thenReturn(1);
        when(requirements.getById(30L)).thenReturn(Requirement.builder().requirementId(30L).projectId(5L).build());

        pipeline.handleError("p-1", "boom");

        verify(notifications).create(eq(8L), eq(5L), eq(NotificationType.PIPELINE_FAILED), eq("문서 생성 실패"),
                eq("boom"), eq(NotificationReferenceType.PIPELINE), eq(9L));
    }

    @Test
    void 다른_회원_알림_읽음_요청은_404() {
        var service = mock(NotificationService.class);
        doThrow(new NotificationNotFoundException(17L)).when(service).markAsRead(8L, 17L);
        var session = new MockHttpSession();
        session.setAttribute("memberId", 8L);

        var response = new NotificationController(service).markAsRead(session, 17L);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void 비로그인_읽음_요청은_401() {
        var service = mock(NotificationService.class);

        var response = new NotificationController(service).markAsRead(new MockHttpSession(), 17L);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(service);
    }
}
