package com.timiroom.infra.slack;

import com.timiroom.domain.spec.service.*;
import com.timiroom.domain.integrationjob.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
@ConditionalOnProperty(name={"integration.enabled","integration.slack.enabled"},havingValue="true")
public class SlackCommandService {
    public static final String HELP="/timiroom connect | spec | review <proposalId> <revision> | pr <snapshotId> <repoId> <pullNumber> <headSha> | status <jobId>\n문서 승인은 티미룸 화면에서 처리합니다.";
    private final JdbcTemplate jdbc;
    private final SlackAccountService accounts;
    private final SpecSnapshotService snapshots;
    private final IntegrationTaskService tasks;
    private final IntegrationJobService jobs;
    @Value("${frontend.url}") private String frontend;
    public String projectLink(Long project) {
        var uri=java.net.URI.create(frontend);
        if(!Set.of("https","http").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
            || uri.getQuery()!=null || uri.getFragment()!=null || ("http".equals(uri.getScheme()) && !Set.of("localhost","127.0.0.1").contains(uri.getHost())))
            throw new IllegalStateException("SLACK_FRONTEND_URL_INVALID");
        return frontend.replaceAll("/+$","")+"/dashboard?projectId="+project;
    }
    public String reviewLink(Long project,UUID proposal) {
        return projectLink(project).replace("/dashboard?","/spec-review?")+"&proposalId="+proposal;
    }
    public String jobLink(Long project,UUID job) {
        return projectLink(project).replace("/dashboard?","/spec-changes?")+"&jobId="+job;
    }
    @Transactional
    public Map<String,String> accept(SlackCommand command,String requestId) {
        var arguments=SlackCommand.parse(command.text());
        if(arguments.action()==SlackCommand.Action.HELP) return privateResponse(HELP);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,1))",Object.class,command.team()+":"+command.user());
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from slack_command_request where request_id=?)",Boolean.class,requestId)))
            return privateResponse("이미 접수한 요청입니다.");
        int recent=jdbc.queryForObject("select count(*) from slack_command_request where team_id=? and user_id=? and created_at>?",Integer.class,
            command.team(),command.user(),Timestamp.from(Instant.now().minusSeconds(60)));
        if(recent>=20) throw new IllegalStateException("RATE_LIMITED");
        if(arguments.action()==SlackCommand.Action.CONNECT) {
            // Same signed callback is not allowed to issue multiple connection codes.
            if(!enqueue(command,requestId,"DONE")) return privateResponse("이미 처리한 연결 요청입니다. /timiroom connect를 새로 실행하세요.");
            return privateResponse("연결 코드 (5분간 유효): "+accounts.issueCode(command)+"\n티미룸의 Slack 연결 화면에 입력하세요.");
        }
        var actor=accounts.actor(command);accounts.require(command,actor);
        if(!enqueue(command,requestId,"QUEUED")) return privateResponse("이미 접수한 요청입니다.");
        return privateResponse("요청을 접수했습니다. 결과는 본인에게 표시되며 검사 완료 알림은 프로젝트 채널로 전달됩니다.");
    }
    private boolean enqueue(SlackCommand command,String id,String state) {
        return jdbc.update("insert into slack_command_request(request_id,team_id,user_id,channel_id,command_text,created_at,state) values(?,?,?,?,?,?,?) on conflict(request_id) do nothing",
            id,command.team(),command.user(),command.channel(),command.text(),Timestamp.from(Instant.now()),state)==1;
    }
    public String execute(SlackCommand command,String requestId) {
        var actor=accounts.actor(command);var channel=accounts.require(command,actor);
        var args=SlackCommand.parse(command.text());var project=channel.projectId();var link=projectLink(project);
        return switch(args.action()) {
            case SPEC -> {
                var snapshot=snapshots.latest(project,actor);
                yield "발행 명세 revision "+snapshot.revision()+"\nsnapshot: "+snapshot.snapshotId()+"\n"+link;
            }
            case REVIEW -> {
                var job=tasks.beginArtifactReview(project,actor,args.id(),args.revision(),"slack:"+requestId);
                yield "문서 교차검증 요청: "+job.jobId()+"\n"+reviewLink(project,args.id());
            }
            case PR -> {
                var job=tasks.beginPullRequestReview(project,actor,args.id(),args.repo(),args.pull(),args.head(),"slack:"+requestId);
                yield "PR 정합성 검증 요청: "+job.jobId()+"\n"+jobLink(project,job.jobId());
            }
            case STATUS -> {
                var job=jobs.get(project,actor,args.id());
                String label=switch(job.kind()) { case SPEC_CHANGE -> "변경안 생성";case ARTIFACT_REVIEW -> "문서 교차검증";case PR_REVIEW -> "PR 정합성 검증"; };
                yield label+" · "+job.status()+"\n"+jobLink(project,job.jobId());
            }
            default -> HELP;
        };
    }
    private Map<String,String> privateResponse(String text) { return Map.of("response_type","ephemeral","text",text); }
}
