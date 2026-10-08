package com.timiroom.domain.spec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.integrationjob.entity.IntegrationJob;
import com.timiroom.domain.integrationjob.repository.IntegrationJobRepository;
import com.timiroom.domain.integrationjob.service.IntegrationJobService;
import com.timiroom.domain.project.entity.Project;
import com.timiroom.domain.project.repository.ProjectRepository;
import com.timiroom.domain.spec.service.DocumentAccessService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class IntegrationJobServiceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final IntegrationJobRepository jobs=mock(IntegrationJobRepository.class);
    final ProjectRepository projects=mock(ProjectRepository.class);
    final DocumentAccessService access=mock(DocumentAccessService.class);
    final IntegrationJobService service=new IntegrationJobService(jobs,projects,access,mapper,List.of());
    void setup() {
        when(projects.findForUpdate(1L)).thenReturn(Optional.of(Project.builder().projectId(1L).build()));
        when(jobs.save(any())).thenAnswer(i->i.getArgument(0));
    }
    @Test void canonicalPayloadReturnsSameJobAndConflictingPayloadFails() throws Exception {
        setup();
        var payload=mapper.readTree("{\"b\":2,\"a\":1}");
        var dto=service.submit(1L,2L,IntegrationJob.Kind.PR_REVIEW,"same",payload,"binding");
        var captor=org.mockito.ArgumentCaptor.forClass(IntegrationJob.class); verify(jobs).save(captor.capture());
        when(jobs.findByProjectIdAndActorIdAndKindAndIdempotencyKey(1L,2L,IntegrationJob.Kind.PR_REVIEW,"same")).thenReturn(Optional.of(captor.getValue()));
        assertThat(service.submit(1L,2L,IntegrationJob.Kind.PR_REVIEW,"same",mapper.readTree("{\"a\":1,\"b\":2}"),"binding").jobId()).isEqualTo(dto.jobId());
        assertThatThrownBy(()->service.submit(1L,2L,IntegrationJob.Kind.PR_REVIEW,"same",mapper.readTree("{\"a\":3}"),"binding"))
            .isInstanceOf(IllegalStateException.class).hasMessage("IDEMPOTENCY_CONFLICT");
        verify(jobs,times(1)).save(any());
    }
    @Test void concurrentWorkAndMinuteBudgetPreventProviderWork() {
        setup();
        when(jobs.countByProjectIdAndActorIdAndStateIn(eq(1L),eq(2L),anyList())).thenReturn(1L);
        assertThatThrownBy(()->service.submit(1L,2L,IntegrationJob.Kind.ARTIFACT_REVIEW,"key",mapper.createObjectNode(),"b"))
            .hasMessage("CONCURRENCY_LIMIT");
        when(jobs.countByProjectIdAndActorIdAndStateIn(eq(1L),eq(2L),anyList())).thenReturn(0L);
        when(jobs.countByProjectIdAndActorIdAndCreatedAtAfter(eq(1L),eq(2L),any())).thenReturn(3L);
        assertThatThrownBy(()->service.submit(1L,2L,IntegrationJob.Kind.ARTIFACT_REVIEW,"key",mapper.createObjectNode(),"b"))
            .hasMessage("RATE_LIMITED");
        verify(jobs,never()).save(any());
    }
    @Test void jobIdsCannotReadAnotherActorOrProject() {
        var job=new IntegrationJob(1L,2L,IntegrationJob.Kind.SPEC_CHANGE,"key","h","{}","b",Instant.now());
        when(jobs.findById(job.getJobId())).thenReturn(Optional.of(job));
        assertThatThrownBy(()->service.get(1L,3L,job.getJobId())).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.get(4L,2L,job.getJobId())).isInstanceOf(SecurityException.class);
    }
}
