package com.timiroom.infra.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.spec.service.DocumentAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
@ConditionalOnProperty(name={"integration.enabled","integration.slack.enabled"},havingValue="true")
public class SlackQueueService {
    private final JdbcTemplate jdbc;
    private final SlackAccountService accounts;
    private final SlackCommandService commands;
    private final DocumentAccessService access;
    private final ObjectMapper mapper;
    public record CommandRequest(String id,SlackCommand command) {}
    public record Notification(String id,Long project,Long actor,String channel,String text,Instant createdAt) {}
    @Transactional
    public Optional<CommandRequest> claimCommand() {
        expire("slack_command_request");
        var rows=jdbc.queryForList("select * from slack_command_request where state='QUEUED' order by created_at for update skip locked limit 1");
        if(rows.isEmpty()) return Optional.empty();var row=rows.getFirst();String id=(String)row.get("request_id");
        jdbc.update("update slack_command_request set state='RUNNING',claimed_at=? where request_id=?",Timestamp.from(Instant.now()),id);
        return Optional.of(new CommandRequest(id,new SlackCommand((String)row.get("team_id"),(String)row.get("user_id"),
            (String)row.get("channel_id"),(String)row.get("command_text"))));
    }
    @Transactional public void commandDone(String id,String error) {
        jdbc.update("update slack_command_request set state=?,error_code=? where request_id=? and state='RUNNING'",
            error==null?"DONE":"FAILED",error,id);
    }
    private void expire(String table) {
        // Table names are internal constants. Never auto-repeat a call with an unknown outcome.
        if(!Set.of("slack_command_request","slack_notification").contains(table)) throw new IllegalArgumentException();
        jdbc.update("update "+table+" set state='FAILED',error_code='OUTCOME_UNKNOWN' where state in ('RUNNING','SENDING') and claimed_at<?",
            Timestamp.from(Instant.now().minusSeconds(300)));
    }
    @Transactional
    public void collectNotifications() {
        // Only jobs created after explicit channel configuration; connecting a channel never floods old history.
        var rows=jdbc.queryForList("select j.*, c.channel_id from integration_job j join slack_project_channel c on c.project_id=j.project_id "
            +"where j.state in ('COMPLETED','FAILED') and j.created_at>=c.configured_at and j.created_at>? "
            +"and not exists(select 1 from slack_notification n where n.event_key='job:' || j.job_id::text) order by j.created_at limit 200",
            Timestamp.from(Instant.now().minusSeconds(172800)));
        for(var row:rows) {
            String job=row.get("job_id").toString(),kind=(String)row.get("kind"),state=(String)row.get("state");
            Long project=((Number)row.get("project_id")).longValue(),actor=((Number)row.get("actor_id")).longValue();
            String text,link=commands.jobLink(project,UUID.fromString(job));
            try {
                if(kind.equals("SPEC_CHANGE") && state.equals("COMPLETED")) {
                    String proposal=mapper.readTree((String)row.get("request_json")).path("proposalId").asText();
                    UUID.fromString(proposal);
                    var ready=jdbc.queryForList("select state from spec_change_proposal where proposal_id=? and project_id=?",UUID.fromString(proposal),project);
                    if(ready.size()!=1 || !"READY".equals(ready.getFirst().get("state"))) continue;
                    text="티미룸 변경안 승인 요청\n"+commands.reviewLink(project,UUID.fromString(proposal));
                } else {
                    String label=kind.equals("ARTIFACT_REVIEW")?"문서 교차검증":kind.equals("PR_REVIEW")?"PR 정합성 검증":"변경안 생성";
                    String outcome=state.equals("FAILED")?"실행 실패":"검토 필요";
                    if(!kind.equals("SPEC_CHANGE") && state.equals("COMPLETED")) {
                        var result=mapper.readTree((String)row.get("result_json"));
                        if(result.path("passed").isBoolean() && result.path("passed").booleanValue()) outcome="PASS";
                    }
                    text="티미룸 "+label+" · "+outcome+"\n작업: "+job+"\n"+link;
                }
            } catch(Exception invalidStoredResult) { continue; }
            jdbc.update("insert into slack_notification(event_key,project_id,actor_id,channel_id,message_text,created_at,state) values(?,?,?,?,?,?,'QUEUED') on conflict(event_key) do nothing",
                "job:"+job,project,actor,row.get("channel_id"),text,Timestamp.from(Instant.now()));
        }
    }
    @Transactional public Optional<Notification> claimNotification() {
        expire("slack_notification");
        var rows=jdbc.queryForList("select * from slack_notification where state='QUEUED' order by created_at for update skip locked limit 1");
        if(rows.isEmpty()) return Optional.empty();var row=rows.getFirst();String id=(String)row.get("event_key");
        jdbc.update("update slack_notification set state='SENDING',claimed_at=? where event_key=?",Timestamp.from(Instant.now()),id);
        return Optional.of(new Notification(id,((Number)row.get("project_id")).longValue(),((Number)row.get("actor_id")).longValue(),
            (String)row.get("channel_id"),(String)row.get("message_text"),((Timestamp)row.get("created_at")).toInstant()));
    }
    public void validateNotification(Notification notification) {
        var channel=accounts.channel(notification.channel());
        if(!channel.projectId().equals(notification.project()) || notification.createdAt().isBefore(channel.configuredAt()))
            throw new SecurityException("ACCESS_DENIED");
        access.requireRead(notification.project(),notification.actor());access.requirePm(notification.project(),channel.configuredBy());
    }
    @Transactional public void notificationDone(String id,String error) {
        jdbc.update("update slack_notification set state=?,error_code=? where event_key=? and state='SENDING'",error==null?"SENT":"FAILED",error,id);
    }
}
