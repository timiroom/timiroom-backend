package com.timiroom.domain.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.notification.repository.NotificationRepository;
import com.timiroom.domain.notification.service.NotificationService;
import com.timiroom.domain.pipeline.entity.PipelineExecution;
import com.timiroom.domain.pipeline.repository.*;
import com.timiroom.domain.pipeline.service.PipelineService;
import com.timiroom.domain.requirement.enums.RequirementStatus;
import com.timiroom.domain.requirement.repository.RequirementRepository;
import com.timiroom.domain.requirement.service.RequirementService;
import com.timiroom.domain.spec.service.ArtifactWriteService;
import com.timiroom.domain.spec.service.DocumentAccessService;
import com.timiroom.domain.usage.service.AiCallService;
import com.timiroom.infra.ragpipeline.RagPipelineClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.hibernate.ddl-auto=update", "spring.flyway.enabled=true",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "PIPELINE_TEST_PG_URL", matches = ".+")
@Import({RequirementService.class, NotificationService.class,
        com.timiroom.domain.spec.service.DocumentAccessService.class, com.timiroom.domain.team.service.TeamService.class,
        com.timiroom.domain.project.service.ProjectDataCleaner.class})
class PipelineStoragePostgresTest {
    private static final String SCHEMA = "pipeline_tx_" + UUID.randomUUID().toString().replace("-", "");
    private static final String URL = System.getenv("PIPELINE_TEST_PG_URL");
    private static final String USER = System.getenv("PIPELINE_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("PIPELINE_TEST_PG_PASSWORD");
    private static final String STORED = """
            {"result":{"prdDocument":{"title":"PRD"},"dbSchema":{"tables":[]},
            "apiSpec":{"endpoints":[]},"storage":{"status":"stored","kafkaPublished":true}}}
            """;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws Exception {
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        properties.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        properties.add("spring.datasource.username", () -> USER);
        properties.add("spring.datasource.password", () -> PASSWORD);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (!SCHEMA.matches("pipeline_tx_[a-f0-9]{32}")) throw new IllegalStateException("Invalid test schema");
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }

    @Autowired PipelineExecutionRepository executions;
    @Autowired PipelineArtifactRepository artifacts;
    @Autowired PipelineResultReceiptRepository receipts;
    @Autowired ArtifactRevisionRepository revisions;
    @Autowired RequirementRepository requirementRows;
    @Autowired NotificationRepository notificationRows;
    @Autowired RequirementService requirements;
    @Autowired NotificationService notifications;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    private final RagPipelineClient rag = mock(RagPipelineClient.class);
    private PipelineService service;
    private String id;
    private Long requirementId;

    @BeforeEach
    void fixture() {
        notificationRows.deleteAll();
        artifacts.deleteAll();
        receipts.deleteAll();
        executions.deleteAll();
        requirementRows.deleteAll();
        // 권한 확인 없이 저장소에 바로 넣는다(이 테스트는 결과 저장만 본다).
        var requirement = requirementRows.save(com.timiroom.domain.requirement.entity.Requirement.builder()
                .projectId(7L).memberId(8L).title("fixture").content("{}").build());
        requirementId = requirement.getRequirementId();
        requirements.updateStatus(requirementId, RequirementStatus.PROCESSING);
        id = UUID.randomUUID().toString();
        executions.save(PipelineExecution.builder().pipelineId(id).requirementId(requirementId).memberId(8L).build());
        service = new PipelineService(rag, executions, artifacts, revisions, requirements, notifications,
                new ObjectMapper(), mock(ArtifactWriteService.class), mock(DocumentAccessService.class),
                mock(AiCallService.class), transactions, receipts);
    }

    @Test
    void notificationFailureRollsBackEveryArtifactAndStatusThenRecoversWithoutRegeneration() {
        jdbc.execute("""
                CREATE FUNCTION reject_test_notification() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'injected final write failure'; END; $$ LANGUAGE plpgsql
                """);
        jdbc.execute("CREATE TRIGGER reject_test_notification BEFORE INSERT ON notification FOR EACH ROW EXECUTE FUNCTION reject_test_notification()");
        try {
            assertThatThrownBy(() -> service.handleComplete(id, STORED)).isInstanceOf(IllegalStateException.class);
            assertThat(artifacts.count()).isZero();
            assertThat(notificationRows.count()).isZero();
            assertThat(requirementRows.findById(requirementId).orElseThrow().getStatus()).isEqualTo(RequirementStatus.PROCESSING);
            assertThat(executions.findByPipelineId(id).orElseThrow().getStatus()).isEqualTo(PipelineExecution.ExecutionStatus.STORAGE_PENDING);
            assertThat(receipts.findById(id).orElseThrow().isCompleted()).isFalse();
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_notification ON notification");
            jdbc.execute("DROP FUNCTION reject_test_notification()");
        }
        service.recoverPendingResults();
        assertCompletedOnce();
        verifyNoInteractions(rag);
    }

    @Test
    void lateConsumerCompletionIsRecoveredAndOwnerCanReadTheResult() {
        service.handleComplete(id, STORED.replace("\"stored\"", "\"pending\""));
        assertThat(artifacts.count()).isZero();
        when(rag.storageStatus(id)).thenReturn(Map.of("status", "stored", "kafkaPublished", true));
        service.recoverPendingResults();
        service.handleComplete(id, STORED);
        assertCompletedOnce();
        assertThat(service.executionState(8L, id)).containsKey("result");
        assertThatThrownBy(() -> service.executionState(99L, id)).isInstanceOf(SecurityException.class);
        verify(rag).storageStatus(id);
    }

    @Test
    void twoReplicasReceivingCompleteSaveOnlyOneBundle() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Void> receive = () -> { start.await(); service.handleComplete(id, STORED); return null; };
            var first = pool.submit(receive);
            var second = pool.submit(receive);
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }
        assertCompletedOnce();
    }

    @Test
    void changedArtifactsCannotOverwriteThePendingSnapshot() {
        service.handleComplete(id, STORED.replace("\"stored\"", "\"pending\""));
        assertThatThrownBy(() -> service.handleComplete(id, STORED.replace("PRD", "different")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(artifacts.count()).isZero();
        assertThat(receipts.findById(id).orElseThrow().getPayload()).contains("PRD");
    }

    @Test
    void migrationUpdatesTheOldPostgresStatusCheck() throws Exception {
        jdbc.execute("ALTER TABLE pipeline_execution DROP CONSTRAINT IF EXISTS pipeline_execution_status_check");
        jdbc.execute("ALTER TABLE pipeline_execution ADD CONSTRAINT pipeline_execution_status_check CHECK (status IN ('RUNNING','COMPLETED','FAILED','RETRYING'))");
        try (var connection = DriverManager.getConnection(URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA, USER, PASSWORD)) {
            var context = mock(org.flywaydb.core.api.migration.Context.class);
            when(context.getConnection()).thenReturn(connection);
            new db.migration.V20261023_02__pipeline_execution_storage_pending().migrate(context);
        }
        service.handleComplete(id, STORED.replace("\"stored\"", "\"pending\""));
        assertThat(executions.findByPipelineId(id).orElseThrow().getStatus()).isEqualTo(PipelineExecution.ExecutionStatus.STORAGE_PENDING);
    }

    private void assertCompletedOnce() {
        assertThat(artifacts.count()).isEqualTo(3);
        assertThat(notificationRows.count()).isEqualTo(1);
        assertThat(requirementRows.findById(requirementId).orElseThrow().getStatus()).isEqualTo(RequirementStatus.COMPLETED);
        assertThat(executions.findByPipelineId(id).orElseThrow().getStatus()).isEqualTo(PipelineExecution.ExecutionStatus.COMPLETED);
        assertThat(receipts.findById(id).orElseThrow().isCompleted()).isTrue();
    }
}
