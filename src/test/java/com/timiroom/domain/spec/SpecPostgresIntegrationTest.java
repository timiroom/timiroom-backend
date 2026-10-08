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
@Import({ArtifactWriteService.class,DocumentAccessService.class,TeamService.class,SpecSnapshotService.class,SpecPostgresIntegrationTest.JsonConfig.class})
class SpecPostgresIntegrationTest {
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
}
