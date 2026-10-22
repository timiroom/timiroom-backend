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
@Import({SlackAccountService.class,SlackCommandService.class,SlackQueueService.class,com.timiroom.infra.slack.SlackWorkspaces.class,SlackPostgresTest.JsonConfig.class})
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
    @MockitoBean com.timiroom.domain.github.PullRequestConsistencyService pullRequests;
    @MockitoBean com.timiroom.domain.aikey.service.SecretCipher cipher;
    @BeforeEach void resetTables() {
        jdbc.update("delete from slack_notification");jdbc.update("delete from slack_command_request");
        jdbc.update("delete from slack_project_channel");jdbc.update("delete from slack_account_link");jdbc.update("delete from slack_link_code");
        jdbc.update("delete from slack_workspace");
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
        assertThat(texts).anyMatch(t->t.startsWith("*문서 교차검증* · :white_check_mark: PASS")).anyMatch(t->t.startsWith("*PR 정합성 검증* · :white_check_mark: PASS"));
        // 긴 주소와 작업 번호 전체 대신 짧은 링크 글씨와 앞 8자리만 보인다
        assertThat(texts).allMatch(t->t.contains("|결과 보기>") && !t.matches("(?s).*작업 `[0-9a-f-]{9,}`.*"));
        assertThat(texts).allMatch(t->!t.contains("private fixture document") && !t.contains("secretOriginal"));
        var notification=queue.claimNotification().orElseThrow();accounts.unlink(2L);
        assertThatThrownBy(()->queue.validateNotification(notification)).isInstanceOf(SecurityException.class);
    }
    @Test void secondWorkspaceKeepsAccountsChannelsAndNotificationsApart() {
        linked();
        var other=new SlackCommand("TOTHER","UOTHER","COTHER","connect");
        // 앱을 설치하지 않은 워크스페이스는 받지 않는다
        assertThatThrownBy(()->accounts.issueCode(other)).isInstanceOf(SecurityException.class);
        jdbc.update("insert into slack_workspace(team_id,team_name,bot_token_enc,installed_at) values('TOTHER','다른 팀','enc',now())");
        accounts.link(2L,accounts.issueCode(other));
        assertThat(accounts.connections(2L)).extracting(r->r.get("team_id")).containsExactly("TTEST","TOTHER");
        assertThat(accounts.connections(2L)).anyMatch(r->"다른 팀".equals(r.get("team_name")));
        // 계정이 둘이면 워크스페이스를 골라야 한다
        assertThatThrownBy(()->accounts.configure(7L,2L,null,"COTHER",null)).isInstanceOf(IllegalArgumentException.class);
        accounts.configure(7L,2L,"TOTHER","COTHER",null);
        assertThat(accounts.channel("TOTHER","COTHER").projectId()).isEqualTo(7L);
        assertThatThrownBy(()->accounts.channel("TTEST","COTHER")).isInstanceOf(SecurityException.class);
        jdbc.update("insert into integration_job(job_id,project_id,actor_id,kind,state,idempotency_key,request_hash,request_json,binding_key,result_json,created_at) "
            +"values(?,7,2,'ARTIFACT_REVIEW','COMPLETED','k','hash','{}','binding','{\"passed\":true}',?)",UUID.randomUUID(),Timestamp.from(Instant.now().plusSeconds(1)));
        queue.collectNotifications();
        assertThat(jdbc.queryForList("select distinct channel_id||':'||team_id from slack_notification order by 1",String.class)).containsExactly("COTHER:TOTHER","CTEST:TTEST");
        // 남이 설치한 워크스페이스는 연결하지 않은 사람에게 보이지 않는다
        var workspaces=new com.timiroom.infra.slack.SlackWorkspaces(jdbc,cipher,"TTEST","");
        jdbc.update("insert into slack_workspace(team_id,team_name,bot_token_enc,installed_by,installed_at) values('TTHIRD','남의 팀','enc',99,now())");
        assertThat(workspaces.visibleTo(2L,List.of("TTEST","TOTHER"))).extracting(w->w.teamId()).containsExactly("TTEST","TOTHER");
        assertThat(workspaces.visibleTo(99L,List.of())).extracting(w->w.teamId()).containsExactly("TTHIRD");
        // 한 워크스페이스만 해제하면 그 워크스페이스의 채널만 사라진다
        accounts.unlink(2L,"TOTHER");
        assertThat(accounts.connections(2L)).extracting(r->r.get("team_id")).containsExactly("TTEST");
        assertThat(accounts.channels(2L)).extracting(r->r.get("channel_id")).containsExactly("CTEST");
    }
}
