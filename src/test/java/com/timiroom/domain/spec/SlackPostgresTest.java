package com.timiroom.domain.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.infra.slack.*;
import com.timiroom.domain.spec.service.*;
import com.timiroom.domain.integrationjob.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.*;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@JdbcTest(properties={"spring.datasource.username=timiroom_test","spring.datasource.password=",
    "spring.datasource.driver-class-name=org.postgresql.Driver","spring.flyway.enabled=true",
    "integration.enabled=true","integration.slack.enabled=true","integration.slack.team-id=TTEST","frontend.url=http://localhost:3000"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({SlackAccountService.class,SlackCommandService.class,SlackQueueService.class,SlackPostgresTest.JsonConfig.class})
@ContextConfiguration(classes=SlackPostgresTest.JsonConfig.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named="TIMIROOM_TEST_POSTGRES",matches="true")
class SlackPostgresTest {
    private static final String URL="jdbc:postgresql://127.0.0.1:55439/timiroom_integration_test";
    private static final String SCHEMA="timiroom_slack_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void schema(DynamicPropertyRegistry r) throws Exception {
        try(var c=java.sql.DriverManager.getConnection(URL,"timiroom_test","");var s=c.createStatement()) {s.execute("CREATE SCHEMA "+SCHEMA);}
        r.add("spring.datasource.url",()->URL+"?currentSchema="+SCHEMA);r.add("spring.flyway.schemas",()->SCHEMA);r.add("spring.flyway.default-schema",()->SCHEMA);
    }
    @Configuration static class JsonConfig { @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();} }
    @Autowired SlackAccountService accounts;
    @Autowired SlackCommandService commands;
    @Autowired SlackQueueService queue;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DocumentAccessService access;
    @MockitoBean SpecSnapshotService snapshots;
    @MockitoBean IntegrationTaskService tasks;
    @MockitoBean IntegrationJobService jobs;
    @BeforeEach void resetTables() {
        jdbc.update("delete from slack_notification");jdbc.update("delete from slack_command_request");
        jdbc.update("delete from slack_project_channel");jdbc.update("delete from slack_account_link");jdbc.update("delete from slack_link_code");
    }
    private SlackCommand command(String text) {return new SlackCommand("TTEST","UTEST","CTEST",text);}
    private void linked() {accounts.link(2L,accounts.issueCode(command("connect")));accounts.configure(7L,2L,"CTEST");}
    @Test void browserIdentityPreservesExistingChannelsAndRejectsReplacement() {
        linked();
        accounts.linkIdentity(2L,"TTEST","UTEST");
        assertThat(accounts.channels(2L)).hasSize(1);
        assertThatThrownBy(()->accounts.linkIdentity(3L,"TTEST","UTEST")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->accounts.linkIdentity(2L,"TTEST","UOTHER")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->accounts.linkIdentity(2L,"TOTHER","UTEST")).isInstanceOf(SecurityException.class);
        assertThat(accounts.actor(command("spec"))).isEqualTo(2L);
    }
    @Test void codeIsHashedSingleUseExpiresAndNeverReplacesAnotherAccount() {
        String code=accounts.issueCode(command("connect"));
        assertThat(jdbc.queryForObject("select code_hash from slack_link_code",String.class)).isNotEqualTo(code);
        accounts.link(2L,code);
        assertThatThrownBy(()->accounts.link(2L,code)).isInstanceOf(SecurityException.class);
        String another=accounts.issueCode(command("connect"));
        assertThatThrownBy(()->accounts.link(3L,another)).isInstanceOf(IllegalStateException.class);
        jdbc.update("update slack_link_code set expires_at=? where code_hash=?",Timestamp.from(Instant.now().minusSeconds(1)),DocumentHash.of(another));
        assertThatThrownBy(()->accounts.link(2L,another)).isInstanceOf(SecurityException.class);
        assertThat(accounts.actor(command("spec"))).isEqualTo(2L);
    }
    @Test void repeatedSignedCommandQueuesOnceAndRevocationStopsExecution() {
        linked();String id="a".repeat(64);
        commands.accept(command("spec"),id);commands.accept(command("spec"),id);
        assertThat(jdbc.queryForObject("select count(*) from slack_command_request",Integer.class)).isEqualTo(1);
        var claimed=queue.claimCommand().orElseThrow();assertThat(claimed.id()).isEqualTo(id);
        assertThat(queue.claimCommand()).isEmpty();accounts.unlink(2L);
        assertThatThrownBy(()->commands.execute(claimed.command(),id)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(snapshots,tasks,jobs);
    }
    @Test void deniedProjectNeverQueuesAndUnknownOutcomeNeverRetries() {
        linked();doThrow(new SecurityException("ACCESS_DENIED")).when(access).requireRead(7L,2L);
        assertThatThrownBy(()->commands.accept(command("spec"),"b".repeat(64))).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("select count(*) from slack_command_request",Integer.class)).isZero();
        reset(access);commands.accept(command("spec"),"c".repeat(64));queue.claimCommand();
        jdbc.update("update slack_command_request set claimed_at=?",Timestamp.from(Instant.now().minusSeconds(301)));
        assertThat(queue.claimCommand()).isEmpty();
        assertThat(jdbc.queryForObject("select error_code from slack_command_request",String.class)).isEqualTo("OUTCOME_UNKNOWN");
    }
    @Test void notificationsSeparateDocumentAndPrPassAndNeverContainOriginals() {
        linked();var now=Timestamp.from(Instant.now().plusSeconds(1));
        for(String kind:List.of("ARTIFACT_REVIEW","PR_REVIEW"))
            jdbc.update("insert into integration_job(job_id,project_id,actor_id,kind,state,idempotency_key,request_hash,request_json,binding_key,result_json,created_at) "
                +"values(?,7,2,?,'COMPLETED',?,'hash','{}','binding',?,?)",UUID.randomUUID(),kind,kind,
                "{\"passed\":true,\"secretOriginal\":\"private fixture document\"}",now);
        queue.collectNotifications();queue.collectNotifications();
        assertThat(jdbc.queryForObject("select count(*) from slack_notification",Integer.class)).isEqualTo(2);
        var texts=jdbc.queryForList("select message_text from slack_notification",String.class);
        assertThat(texts).anyMatch(t->t.contains("문서 교차검증 · PASS")).anyMatch(t->t.contains("PR 정합성 검증 · PASS"));
        assertThat(texts).allMatch(t->!t.contains("private fixture document") && !t.contains("secretOriginal"));
        var notification=queue.claimNotification().orElseThrow();accounts.unlink(2L);
        assertThatThrownBy(()->queue.validateNotification(notification)).isInstanceOf(SecurityException.class);
    }
}
