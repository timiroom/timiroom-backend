package com.timiroom.domain.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.pipeline.entity.*;
import com.timiroom.domain.pipeline.repository.*;
import com.timiroom.domain.pipeline.service.PipelineService;
import com.timiroom.domain.project.entity.*;
import com.timiroom.domain.project.entity.mapping.ProjectMember;
import com.timiroom.domain.project.enums.ProjectRole;
import com.timiroom.domain.project.repository.*;
import com.timiroom.domain.requirement.entity.Requirement;
import com.timiroom.domain.requirement.repository.RequirementRepository;
import com.timiroom.domain.team.entity.Team;
import com.timiroom.domain.team.entity.mapping.TeamMember;
import com.timiroom.domain.team.repository.*;
import com.timiroom.domain.team.service.TeamService;
import com.timiroom.domain.spec.dto.ArtifactWriteCommand;
import com.timiroom.domain.spec.dto.*;
import com.timiroom.domain.spec.entity.SpecChangeProposal;
import com.timiroom.domain.spec.repository.*;
import com.timiroom.domain.integrationjob.entity.IntegrationJob;
import com.timiroom.domain.integrationjob.repository.IntegrationJobRepository;
import com.timiroom.domain.integrationjob.service.IntegrationJobService;
import com.timiroom.domain.spec.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties={
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55439/timiroom_integration_test",
    "spring.datasource.username=timiroom_test","spring.datasource.password=",
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named="TIMIROOM_TEST_POSTGRES",matches="true")
@Import({ArtifactWriteService.class,DocumentAccessService.class,TeamService.class,SpecSnapshotService.class,SpecChangeService.class,IntegrationJobService.class,
    com.timiroom.domain.integrationjob.service.IntegrationTaskService.class,com.timiroom.domain.integrationjob.service.TimiroomJobHandler.class,
    ArtifactReviewService.class,SpecPostgresIntegrationTest.JsonConfig.class})
class SpecPostgresIntegrationTest {
    private static final String TEST_URL="jdbc:postgresql://127.0.0.1:55439/timiroom_integration_test";
    private static final String SCHEMA="timiroom_spec_"+UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void isolatedSchema(DynamicPropertyRegistry registry) throws Exception {
        // A fresh schema makes every test run execute all migrations, without stale Flyway history
        // surviving Hibernate create-drop from a previous run.
        try(var connection=java.sql.DriverManager.getConnection(TEST_URL,"timiroom_test","");var statement=connection.createStatement()) {
            try(var result=statement.executeQuery("select current_database()")) {
                result.next(); if(!"timiroom_integration_test".equals(result.getString(1))) throw new IllegalStateException("Wrong test database");
            }
            statement.execute("CREATE SCHEMA "+SCHEMA);
        }
        registry.add("spring.datasource.url",()->TEST_URL+"?currentSchema="+SCHEMA);
        registry.add("spring.flyway.schemas",()->SCHEMA);
        registry.add("spring.flyway.default-schema",()->SCHEMA);
    }
    @TestConfiguration static class JsonConfig {
        @Bean ObjectMapper objectMapper(){return new ObjectMapper().findAndRegisterModules();}
    }
    @Autowired ArtifactWriteService writer;
    @Autowired SpecSnapshotService snapshots;
    @Autowired PipelineArtifactRepository artifacts;
    @Autowired ArtifactRevisionRepository revisions;
    @Autowired PipelineExecutionRepository executions;
    @Autowired RequirementRepository requirements;
    @Autowired ProjectRepository projects;
    @Autowired ProjectMemberRepository members;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository teamMembers;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PipelineService pipeline;
    @MockitoBean ArtifactReviewResultReader reviewResults;
    @Autowired SpecChangeService changes;
    @Autowired SpecChangeProposalRepository proposals;
    @Autowired SpecSnapshotRepository snapshotRepository;
    @Autowired ObjectMapper mapper;
    @Autowired IntegrationJobService jobService;
    @Autowired IntegrationJobRepository jobs;
    @Autowired com.timiroom.domain.integrationjob.service.IntegrationTaskService tasks;
    @Autowired com.timiroom.domain.integrationjob.service.TimiroomJobHandler handler;
    @MockitoBean DocumentEditService editor;
    @MockitoBean DocumentImpactService impact;
    @MockitoBean com.timiroom.infra.consistency.ConsistencyServiceClient consistency;
    @MockitoBean com.timiroom.domain.github.PullRequestConsistencyService pullRequests;
    Long projectId;
    List<PipelineArtifact> docs;

    @BeforeEach void fixture(){
        assertThat(jdbc.queryForObject("select current_database()",String.class)).isEqualTo("timiroom_integration_test");
        var team=teams.save(Team.builder().teamName("fixture-"+UUID.randomUUID()).build());
        teamMembers.save(TeamMember.builder().teamId(team.getTeamId()).memberId(2L).build());
        var project=projects.save(Project.builder().teamId(team.getTeamId()).projectName("fixture").build());
        projectId=project.getProjectId();
        members.save(ProjectMember.builder().projectId(projectId).memberId(2L).projectRole(ProjectRole.PM).build());
        var requirement=requirements.save(Requirement.builder().projectId(projectId).memberId(2L).title("fixture").content("{}").build());
        var execution=executions.save(PipelineExecution.builder().pipelineId(UUID.randomUUID().toString()).requirementId(requirement.getRequirementId()).memberId(2L).status(PipelineExecution.ExecutionStatus.COMPLETED).build());
        docs=new ArrayList<>();
        for(var type:List.of(PipelineArtifact.ArtifactType.PRD,PipelineArtifact.ArtifactType.API_SPEC,PipelineArtifact.ArtifactType.DB_SCHEMA))
            docs.add(artifacts.save(PipelineArtifact.builder().executionId(execution.getExecutionId()).artifactType(type).content("original-"+type).build()));
        when(pipeline.getLatestArtifactsByProject(projectId)).thenReturn(docs);
    }
    @Test void databaseFailureRollsBackAllDocumentsAndHistory(){
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION timiroom_test_reject_content() RETURNS trigger AS $$
            BEGIN IF NEW.content = 'reject-test-write' THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END;
            $$ LANGUAGE plpgsql
            """);
        jdbc.execute("CREATE TRIGGER timiroom_test_write_failure BEFORE UPDATE ON pipeline_artifact FOR EACH ROW EXECUTE FUNCTION timiroom_test_reject_content()");
        try {
            assertThatThrownBy(()->writer.writeBatch(projectId,2L,List.of(
                new ArtifactWriteCommand(docs.get(0).getArtifactId(),1,null,"updated"),
                new ArtifactWriteCommand(docs.get(1).getArtifactId(),1,null,"reject-test-write")))).isInstanceOf(RuntimeException.class);
            for(var original:docs) {
                var stored=artifacts.findById(original.getArtifactId()).orElseThrow();
                assertThat(stored.getContent()).isEqualTo(original.getContent());
                assertThat(stored.getVersion()).isEqualTo(1);
                assertThat(revisions.findFirstByArtifactIdOrderByVersionDesc(stored.getArtifactId())).isEmpty();
            }
        } finally {jdbc.execute("DROP TRIGGER IF EXISTS timiroom_test_write_failure ON pipeline_artifact");}
    }
    @Test void concurrentPublicationAllocatesUniqueRevisions() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var jobs=pool.invokeAll(List.of(()->snapshots.publish(projectId,2L).revision(),()->snapshots.publish(projectId,2L).revision()));
            assertThat(List.of(jobs.get(0).get(),jobs.get(1).get())).containsExactlyInAnyOrder(1,2);
        }
    }
    @Test void otherTeamCannotModifyDocument(){
        assertThatThrownBy(()->writer.write(projectId,999L,docs.get(0).getArtifactId(),1,"bad"))
            .isInstanceOf(SecurityException.class);
        assertThat(artifacts.findById(docs.get(0).getArtifactId()).orElseThrow().getContent()).isEqualTo("original-PRD");
    }
    UUID readyProposal() {
        var base=snapshots.publish(projectId,2L);
        var p=changes.create(projectId,2L,base.snapshotId(),new ChangeInstruction(List.of(PipelineArtifact.ArtifactType.API_SPEC),"경로 수정",List.of()));
        changes.complete(projectId,2L,p.getProposalId(),new DocumentEditResult(List.of(
            new DocumentEditResult.Change(PipelineArtifact.ArtifactType.API_SPEC,mapper.createObjectNode().put("path","/new"),mapper.createArrayNode(),"path")),"test"),
            new DocumentImpactResult(base.snapshotId(),null,"none",List.of(),"NONE"));
        when(reviewResults.hasPass(eq(p.getProposalId()),eq(1),anyString())).thenReturn(true);
        return p.getProposalId();
    }
    @Test void snapshotInsertFailureRollsBackApprovalAndOriginals() {
        var id=readyProposal();
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION timiroom_test_reject_snapshot() RETURNS trigger AS $$
            BEGIN IF NEW.revision > 1 THEN RAISE EXCEPTION 'test publication failure'; END IF; RETURN NEW; END;
            $$ LANGUAGE plpgsql
            """);
        jdbc.execute("CREATE TRIGGER timiroom_test_snapshot_failure BEFORE INSERT ON spec_snapshot FOR EACH ROW EXECUTE FUNCTION timiroom_test_reject_snapshot()");
        try {
            assertThatThrownBy(()->changes.approve(projectId,2L,id,1)).isInstanceOf(RuntimeException.class);
            for(var doc:docs) {
                assertThat(artifacts.findById(doc.getArtifactId()).orElseThrow().getVersion()).isEqualTo(1);
                assertThat(revisions.findFirstByArtifactIdOrderByVersionDesc(doc.getArtifactId())).isEmpty();
            }
            assertThat(proposals.findById(id).orElseThrow().getState()).isEqualTo(SpecChangeProposal.State.READY);
            assertThat(snapshotRepository.findFirstByProjectIdOrderByRevisionDesc(projectId).orElseThrow().getRevision()).isEqualTo(1);
        } finally { jdbc.execute("DROP TRIGGER IF EXISTS timiroom_test_snapshot_failure ON spec_snapshot"); }
    }
    @Test void concurrentlyApprovingSameProposalAppliesOnce() throws Exception {
        var id=readyProposal();
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> approve=()->{try{changes.approve(projectId,2L,id,1);return true;}catch(IllegalStateException expected){return false;}};
            var jobs=pool.invokeAll(List.of(approve,approve));
            assertThat(List.of(jobs.get(0).get(),jobs.get(1).get())).containsExactlyInAnyOrder(true,false);
        }
        assertThat(artifacts.findById(docs.get(1).getArtifactId()).orElseThrow().getVersion()).isEqualTo(2);
        assertThat(snapshotRepository.findFirstByProjectIdOrderByRevisionDesc(projectId).orElseThrow().getRevision()).isEqualTo(2);
    }
    @Test void siblingEditedAfterProposalPreventsAllApplication() {
        var id=readyProposal();
        writer.write(projectId,2L,docs.getFirst().getArtifactId(),1,"new unrelated source edit");
        assertThatThrownBy(()->changes.approve(projectId,2L,id,1)).isInstanceOf(IllegalStateException.class).hasMessageContaining("SPEC_CONFLICT");
        assertThat(artifacts.findById(docs.get(1).getArtifactId()).orElseThrow().getVersion()).isEqualTo(1);
        assertThat(proposals.findById(id).orElseThrow().getState()).isEqualTo(SpecChangeProposal.State.READY);
    }
    @Test void newPipelineArtifactsCannotReplaceApprovedBaselineSilently() {
        var id=readyProposal();
        var replacement=new ArrayList<PipelineArtifact>();
        for(var doc:docs) replacement.add(artifacts.save(PipelineArtifact.builder().executionId(doc.getExecutionId())
            .artifactType(doc.getArtifactType()).content("new execution documents").build()));
        when(pipeline.getLatestArtifactsByProject(projectId)).thenReturn(replacement);
        assertThatThrownBy(()->changes.approve(projectId,2L,id,1)).isInstanceOf(IllegalStateException.class).hasMessageContaining("SPEC_CONFLICT");
        assertThat(artifacts.findById(docs.get(1).getArtifactId()).orElseThrow().getVersion()).isEqualTo(1);
        assertThat(proposals.findById(id).orElseThrow().getState()).isEqualTo(SpecChangeProposal.State.READY);
    }
    @Test void concurrentIdempotentSubmissionCreatesOneDatabaseJob() throws Exception {
        var payload=mapper.createObjectNode().put("proposalId","fixture");
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<UUID> submit=()->jobService.submit(projectId,2L,IntegrationJob.Kind.ARTIFACT_REVIEW,"key",payload,"fixture-binding").jobId();
            var results=pool.invokeAll(List.of(submit,submit));
            assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
        }
        assertThatThrownBy(()->jobService.submit(projectId,2L,IntegrationJob.Kind.ARTIFACT_REVIEW,"key",mapper.createObjectNode(),"fixture-binding"))
            .hasMessage("IDEMPOTENCY_CONFLICT");
    }
    @Test void databaseLeaseRecoveryRejectsLostWorkerAndStopsAtTwoAttempts() {
        jobs.deleteAll(); // Every row here is an isolated test fixture.
        var dto=jobService.submit(projectId,2L,IntegrationJob.Kind.PR_REVIEW,"lease-key",mapper.createObjectNode(),"lease-binding");
        var first=jobService.claim().orElseThrow();
        assertThat(first.getJobId()).isEqualTo(dto.jobId());
        jdbc.update("update integration_job set lease_until=now()-interval '1 second' where job_id=?",dto.jobId());
        var second=jobService.claim().orElseThrow();
        assertThat(second.getAttempts()).isEqualTo(2);
        assertThatThrownBy(()->jobService.complete(dto.jobId(),first.getLeaseId(),mapper.createObjectNode())).hasMessage("JOB_LEASE_EXPIRED");
        jdbc.update("update integration_job set lease_until=now()-interval '1 second' where job_id=?",dto.jobId());
        assertThat(jobService.claim()).isEmpty();
        assertThat(jobService.get(projectId,2L,dto.jobId()).status()).isEqualTo(IntegrationJob.State.FAILED);
    }
    @Test void persistedManualJobReviewAndApprovalPreserveOriginalsUntilBoundPass() throws Exception {
        jobs.deleteAll(); // Disposable test schema only.
        for(var doc:docs) writer.write(projectId,2L,doc.getArtifactId(),1,"{}");
        var base=snapshots.publish(projectId,2L);
        var source=mapper.readTree("{\"path\":\"/new\"}");
        var instruction=new ChangeInstruction(List.of(PipelineArtifact.ArtifactType.API_SPEC,PipelineArtifact.ArtifactType.DB_SCHEMA),"직접 수정",List.of());
        var input=Map.of(PipelineArtifact.ArtifactType.API_SPEC,source);
        var hashes=Map.of(PipelineArtifact.ArtifactType.API_SPEC,DocumentHash.of("{}"));
        var generated=tasks.beginManualChange(projectId,2L,base.snapshotId(),instruction,input,hashes,"manual-key");
        assertThat(tasks.beginManualChange(projectId,2L,base.snapshotId(),instruction,input,hashes,"manual-key").jobId()).isEqualTo(generated.jobId());
        when(impact.analyze(any(),any(),any())).thenReturn(new DocumentImpactResult(base.snapshotId(),null,"연결 DB 수정",List.of(
            new DocumentImpactResult.Update(PipelineArtifact.ArtifactType.DB_SCHEMA,"관련 테이블",mapper.readTree("{\"tables\":[]}"))),"fixture-existing-impact"));
        var claimed=jobService.claim().orElseThrow();jobService.providerStarted(claimed.getJobId(),claimed.getLeaseId());
        jobService.complete(claimed.getJobId(),claimed.getLeaseId(),handler.execute(claimed));
        verifyNoInteractions(editor);
        var proposalId=UUID.fromString(jobService.get(projectId,2L,generated.jobId()).result().path("proposalId").asText());
        var proposal=changes.get(projectId,2L,proposalId);
        assertThat(proposal.getState()).isEqualTo(SpecChangeProposal.State.READY);
        for(var doc:docs) assertThat(artifacts.findById(doc.getArtifactId()).orElseThrow().getVersion()).isEqualTo(2);
        var reader=new PersistedArtifactReviewResultReader(jobs,mapper);
        when(reviewResults.hasPass(eq(proposalId),eq(1),anyString())).thenAnswer(call->reader.hasPass(call.getArgument(0),call.getArgument(1),call.getArgument(2)));
        assertThatThrownBy(()->changes.approve(projectId,2L,proposalId,1)).hasMessage("ARTIFACT_REVIEW_REQUIRED");
        // A synthetic PR PASS with the same binding cannot authorize a document approval.
        String binding=proposalId+":1:"+proposal.getResultHash();
        var pr=jobService.submit(projectId,2L,IntegrationJob.Kind.PR_REVIEW,"synthetic-pr",mapper.createObjectNode(),binding);
        claimed=jobService.claim().orElseThrow();
        jobService.complete(pr.jobId(),claimed.getLeaseId(),mapper.readTree("{\"passed\":true,\"findings\":[{\"severity\":\"PASS\"}]}"));
        assertThat(reader.hasPass(proposalId,1,proposal.getResultHash())).isFalse();
        when(consistency.reviewArtifacts(anyMap())).thenReturn(mapper.readTree("{\"passed\":true,\"findings\":[{\"severity\":\"PASS\"}]}"));
        var review=tasks.beginArtifactReview(projectId,2L,proposalId,1,"artifact-review");
        claimed=jobService.claim().orElseThrow();jobService.providerStarted(claimed.getJobId(),claimed.getLeaseId());
        jobService.complete(review.jobId(),claimed.getLeaseId(),handler.execute(claimed));
        assertThat(reader.hasPass(proposalId,1,proposal.getResultHash())).isTrue();
        var published=changes.approve(projectId,2L,proposalId,1);
        assertThat(published.revision()).isEqualTo(2);
        assertThat(changes.get(projectId,2L,proposalId).getState()).isEqualTo(SpecChangeProposal.State.APPROVED);
        assertThat(artifacts.findById(docs.getFirst().getArtifactId()).orElseThrow().getVersion()).isEqualTo(2);
        assertThat(artifacts.findById(docs.get(1).getArtifactId()).orElseThrow().getVersion()).isEqualTo(3);
        assertThat(artifacts.findById(docs.get(2).getArtifactId()).orElseThrow().getVersion()).isEqualTo(3);
        assertThat(reader.hasPass(proposalId,2,proposal.getResultHash())).isFalse();
    }
    @Test void persistedProviderJournalPreventsAutomaticSecondAttempt() {
        jobs.deleteAll();
        var request=jobService.submit(projectId,2L,IntegrationJob.Kind.PR_REVIEW,"paid-attempt",mapper.createObjectNode(),"fixture");
        var claimed=jobService.claim().orElseThrow();jobService.providerStarted(claimed.getJobId(),claimed.getLeaseId());
        jdbc.update("update integration_job set lease_until=now()-interval '1 second' where job_id=?",request.jobId());
        assertThat(jobService.claim()).isEmpty();
        assertThat(jobService.get(projectId,2L,request.jobId()).error()).isEqualTo("PROVIDER_OUTCOME_UNKNOWN");
        assertThat(jobs.findById(request.jobId()).orElseThrow().getAttempts()).isEqualTo(1);
    }
}
