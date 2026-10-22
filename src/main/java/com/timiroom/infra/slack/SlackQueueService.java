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
    private final SlackWorkspaces workspaces;
    private final SlackCommandService commands;
    private final DocumentAccessService access;
    private final ObjectMapper mapper;
    public record CommandRequest(String id,SlackCommand command) {}
    /** AI 때문에 실패한 작업은 알림에 이유를 한 줄 붙인다(내 AI 키를 고치면 되는지, 잠시 뒤 다시 하면 되는지) */
    static final Map<String,String> AI_FAILURE_REASONS=Map.of(
        "AI_KEY_INVALID","연결한 AI 키가 올바르지 않습니다. 설정 > 내 AI 키를 확인해 주세요.",
        "AI_KEY_FORBIDDEN","연결한 AI 키에 이 모델 권한이 없습니다. 설정 > 내 AI 키를 확인해 주세요.",
        "AI_MODEL_NOT_FOUND","연결한 AI 키에서 고른 모델을 찾지 못했습니다. 설정 > 내 AI 키를 확인해 주세요.",
        "AI_KEY_QUOTA_EXCEEDED","연결한 AI 키의 크레딧을 모두 썼습니다.",
        "AI_BUSY","AI 요청이 잠시 몰렸습니다. 잠시 후 다시 요청해 주세요.",
        "AI_TIMEOUT","AI 응답이 오래 걸려 중단했습니다. 잠시 후 다시 요청해 주세요.",
        "AI_SERVICE_UNAVAILABLE","AI에 잠시 연결할 수 없었습니다. 잠시 후 다시 요청해 주세요.",
        "AI_INPUT_TOO_LONG","문서가 AI가 한 번에 읽을 수 있는 길이를 넘었습니다.");
    /** team: 보낼 Slack 워크스페이스(예전 행은 비어 있으면 설정값 워크스페이스) */
    public record Notification(String id,Long project,Long actor,String team,String channel,String text,Instant createdAt) {}
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
        // 프로젝트에 채널이 여럿이면 채널마다, 그 채널이 고른 알림 종류(CHANGE·REVIEW·PR)만 보낸다.
        var rows=jdbc.queryForList("select j.*, c.channel_id, c.team_id as slack_team from integration_job j join slack_project_channel c on c.project_id=j.project_id "
            +"where j.state in ('COMPLETED','FAILED') and j.created_at>=c.configured_at and j.created_at>? "
            +"and position((case j.kind when 'SPEC_CHANGE' then 'CHANGE' when 'ARTIFACT_REVIEW' then 'REVIEW' else 'PR' end) in c.events)>0 "
            +"and not exists(select 1 from slack_notification n where n.event_key='job:' || j.job_id::text "
            +"or n.event_key='job:' || j.job_id::text || ':' || c.channel_id) order by j.created_at limit 200",
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
                    text="*변경안 승인 요청* · "+SlackCommandService.escape(commands.projectName(project))+"\n"
                        +"AI가 만든 명세 변경안이 PM 승인을 기다립니다. "+SlackCommandService.link(commands.reviewLink(project,UUID.fromString(proposal)),"변경안 열기");
                } else {
                    String label=kind.equals("ARTIFACT_REVIEW")?"문서 교차검증":kind.equals("PR_REVIEW")?"PR 정합성 검증":"변경안 생성";
                    String outcome=state.equals("FAILED")?":x: 실행 실패":":warning: 검토 필요";
                    if(!kind.equals("SPEC_CHANGE") && state.equals("COMPLETED")) {
                        var result=mapper.readTree((String)row.get("result_json"));
                        if(result.path("passed").isBoolean() && result.path("passed").booleanValue()) outcome=":white_check_mark: PASS";
                    }
                    String reason=state.equals("FAILED")?AI_FAILURE_REASONS.get(String.valueOf(row.get("error_code"))):null;
                    text="*"+label+"* · "+outcome+" · "+SlackCommandService.escape(commands.projectName(project))+"\n"
                        +(reason!=null?reason+"\n":"")
                        +"작업 `"+job.substring(0,8)+"` · "+SlackCommandService.link(link,"결과 보기");
                }
            } catch(Exception invalidStoredResult) { continue; }
            jdbc.update("insert into slack_notification(event_key,project_id,actor_id,team_id,channel_id,message_text,created_at,state) values(?,?,?,?,?,?,?,'QUEUED') on conflict(event_key) do nothing",
                "job:"+job+":"+row.get("channel_id"),project,actor,row.get("slack_team"),row.get("channel_id"),text,Timestamp.from(Instant.now()));
        }
        collectPullRequestChecks();
    }
    /** GitHub 웹훅으로 PR마다 자동으로 돈 정합성 검사 결과도 채널에 알린다. 커밋(head)마다 한 번씩. */
    private void collectPullRequestChecks() {
        // 같은 트랜잭션 안이라 조회가 실패하면 앞의 알림까지 되돌려진다. 표가 있는지 먼저 확인한다.
        Boolean ready=jdbc.queryForObject("select to_regclass('github_pull_request_review_record') is not null",Boolean.class);
        if(!Boolean.TRUE.equals(ready)) return;
        List<Map<String,Object>> rows;
        try {
            rows=jdbc.queryForList("select r.github_pr_review_record_id,r.project_id,r.pull_number,r.head_sha,r.score,r.pull_title,r.pull_url,r.review_url,"
                +"r.check_run_url,c.channel_id,c.team_id as slack_team,c.configured_by from github_pull_request_review_record r join slack_project_channel c on c.project_id=r.project_id "
                +"where r.updated_at>=c.configured_at and r.updated_at>? and position('PR' in c.events)>0 and not exists(select 1 from slack_notification n "
                +"where n.event_key='prauto:' || r.github_pr_review_record_id || ':' || r.head_sha || ':' || c.channel_id) order by r.updated_at limit 50",
                Timestamp.from(Instant.now().minusSeconds(172800)));
        } catch(org.springframework.dao.DataAccessException missingTable) { return; }
        for(var row:rows) {
            Object score=row.get("score");
            String title=String.valueOf(row.getOrDefault("pull_title","")).replaceAll("[<>&*_~`]","");
            if(title.length()>80) title=title.substring(0,80)+"…";
            Long project=((Number)row.get("project_id")).longValue();
            Object result=row.get("review_url")!=null?row.get("review_url"):row.get("check_run_url")!=null?row.get("check_run_url"):row.get("pull_url");
            String text="*PR #"+row.get("pull_number")+" 자동 검사*"+(score!=null?" · 정합성 "+score+"점":"")+" · "+SlackCommandService.escape(commands.projectName(project))+"\n"
                +title+(result!=null?" · "+SlackCommandService.link(String.valueOf(result),"결과 보기"):"");
            jdbc.update("insert into slack_notification(event_key,project_id,actor_id,team_id,channel_id,message_text,created_at,state) values(?,?,?,?,?,?,?,'QUEUED') on conflict(event_key) do nothing",
                "prauto:"+row.get("github_pr_review_record_id")+":"+row.get("head_sha")+":"+row.get("channel_id"),((Number)row.get("project_id")).longValue(),
                ((Number)row.get("configured_by")).longValue(),row.get("slack_team"),row.get("channel_id"),text,Timestamp.from(Instant.now()));
        }
    }
    @Transactional public Optional<Notification> claimNotification() {
        expire("slack_notification");
        var rows=jdbc.queryForList("select * from slack_notification where state='QUEUED' order by created_at for update skip locked limit 1");
        if(rows.isEmpty()) return Optional.empty();var row=rows.getFirst();String id=(String)row.get("event_key");
        jdbc.update("update slack_notification set state='SENDING',claimed_at=? where event_key=?",Timestamp.from(Instant.now()),id);
        return Optional.of(new Notification(id,((Number)row.get("project_id")).longValue(),((Number)row.get("actor_id")).longValue(),
            workspaces.resolve((String)row.get("team_id")),(String)row.get("channel_id"),(String)row.get("message_text"),((Timestamp)row.get("created_at")).toInstant()));
    }
    public void validateNotification(Notification notification) {
        var channel=accounts.channel(notification.team(),notification.channel());
        if(!channel.projectId().equals(notification.project()) || notification.createdAt().isBefore(channel.configuredAt()))
            throw new SecurityException("ACCESS_DENIED");
        access.requireRead(notification.project(),notification.actor());access.requirePm(notification.project(),channel.configuredBy());
    }
    @Transactional public void notificationDone(String id,String error) {
        jdbc.update("update slack_notification set state=?,error_code=? where event_key=? and state='SENDING'",error==null?"SENT":"FAILED",error,id);
    }
}
