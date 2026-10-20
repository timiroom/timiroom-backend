package com.timiroom.infra.slack;

import com.timiroom.domain.github.PullRequestConsistencyService;
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
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor
@ConditionalOnProperty(name={"integration.enabled","integration.slack.enabled"},havingValue="true")
public class SlackCommandService {
    public static final String HELP=String.join("\n",
        "*티미룸 Slack 명령* (한글로도 쓸 수 있습니다)",
        "• `/timiroom` 이 채널 프로젝트의 현황 (기준 명세, 승인 대기, 바로가기)",
        "• `/timiroom 대기` 승인을 기다리는 변경안 목록과 바로가기",
        "• `/timiroom 검증` 가장 최근 대기 변경안의 문서 교차검증 (`검증 21c3a5c5`처럼 ID 앞부분 지정 가능)",
        "• `/timiroom pr 12` 연결된 레포의 PR #12 정합성 검사 (레포가 여럿이면 `pr 레포이름 12`)",
        "• `/timiroom 상태` 최근 작업 결과 (`상태 <작업 ID 앞부분>`)",
        "• `/timiroom 명세` 현재 기준 명세 버전",
        "• `/timiroom 채널연결 [프로젝트 이름]` PM이 이 채널을 프로젝트 알림 채널로 연결 (채널 여러 개 가능) · `/timiroom 연결해제`",
        "• `/timiroom 알림` 이 채널이 받는 알림 보기 · `/timiroom 알림 변경안 교차검증 pr`처럼 골라 받기 · `/timiroom 알림 전체`",
        "• `/timiroom 계정연결` Slack 계정 연결 코드 받기 (티미룸 설정 > 연결된 도구에서 바로 연결해도 됩니다)",
        "승인과 거절은 티미룸 화면에서 합니다.");
    private static final Map<String,String> DOC_LABELS=Map.of("PRD","PRD","API_SPEC","API 명세","DB_SCHEMA","DB 스키마","FEATURE_LIST","기능 명세");
    private static final Map<String,String> JOB_LABELS=Map.of("SPEC_CHANGE","변경안 생성","ARTIFACT_REVIEW","문서 교차검증","PR_REVIEW","PR 정합성 검사");
    private static final Map<String,String> STATE_LABELS=Map.of("QUEUED","대기","RUNNING","진행 중","COMPLETED","완료","FAILED","실패");
    private final JdbcTemplate jdbc;
    private final SlackAccountService accounts;
    private final SpecSnapshotService snapshots;
    private final IntegrationTaskService tasks;
    private final IntegrationJobService jobs;
    private final PullRequestConsistencyService pullRequests;
    @Value("${frontend.url}") private String frontend;

    /** 화면에 그대로 보여 줄 수 있는 안내로 바꾼다. 내부 오류 내용은 드러내지 않는다. */
    public static String friendly(RuntimeException failure) {
        String code=String.valueOf(failure.getMessage());
        if(code.startsWith("SLACK_ACCOUNT_NOT_LINKED")) return "Slack 계정이 티미룸에 연결되어 있지 않습니다. 티미룸 설정 > 연결된 도구에서 'Slack 연결'을 누르거나 `/timiroom 계정연결`로 받은 코드를 입력하세요.";
        if(code.startsWith("SLACK_CHANNEL_NOT_LINKED")) return "이 채널은 아직 티미룸 프로젝트와 연결되지 않았습니다. PM이 이 채널에서 `/timiroom 채널연결 <프로젝트 이름>`을 실행하세요.";
        if(code.startsWith("SLACK_CHANNEL_INVALID")) return "개인 메시지(DM)는 프로젝트 채널로 연결할 수 없습니다. 팀 채널에서 실행하세요.";
        if(code.startsWith("RATE_LIMITED")) return "요청이 너무 많습니다. 잠시 후 다시 시도하세요.";
        if(code.startsWith("SPEC_CONFLICT")) return "변경안이나 기준 명세가 그사이 바뀌었습니다. `/timiroom 대기`로 다시 확인하세요.";
        if(failure instanceof SecurityException) return "이 프로젝트에 대한 권한이 없습니다. 채널 연결은 프로젝트 PM만 할 수 있습니다.";
        if(failure instanceof IllegalArgumentException) return "명령을 이해하지 못했습니다.\n"+HELP;
        return "요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.";
    }
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
            if(!enqueue(command,requestId,"DONE")) return privateResponse("이미 처리한 연결 요청입니다. /timiroom 계정연결을 새로 실행하세요.");
            return privateResponse("연결 코드 (5분간 유효): "+accounts.issueCode(command)+"\n티미룸 설정 > 연결된 도구의 Slack 연결 화면에 입력하세요.");
        }
        var actor=accounts.actor(command);
        // 채널 연결·해제는 이 채널이 아직 프로젝트와 이어지지 않았어도 바로 처리한다.
        if(arguments.action()==SlackCommand.Action.LINK) {
            enqueue(command,requestId,"DONE");
            return privateResponse(linkChannel(command,actor,arguments.query()));
        }
        if(arguments.action()==SlackCommand.Action.NOTIFY) {
            enqueue(command,requestId,"DONE");
            return privateResponse(notifySettings(command,actor,arguments.query()));
        }
        if(arguments.action()==SlackCommand.Action.UNLINK) {
            enqueue(command,requestId,"DONE");
            var channel=accounts.channel(command.channel());accounts.removeChannel(channel.projectId(),actor,command.channel());
            return privateResponse("이 채널과 '"+projectName(channel.projectId())+"' 프로젝트의 연결을 끊었습니다.");
        }
        accounts.require(command,actor);
        if(!enqueue(command,requestId,"QUEUED")) return privateResponse("이미 접수한 요청입니다.");
        return privateResponse(switch(arguments.action()) {
            case REVIEW, PR -> "요청을 접수했습니다. 시작되면 작업 번호를 알려 드리고, 결과는 이 채널에 알립니다.";
            default -> "확인하고 있습니다…";
        });
    }
    private boolean enqueue(SlackCommand command,String id,String state) {
        return jdbc.update("insert into slack_command_request(request_id,team_id,user_id,channel_id,command_text,created_at,state) values(?,?,?,?,?,?,?) on conflict(request_id) do nothing",
            id,command.team(),command.user(),command.channel(),command.text(),Timestamp.from(Instant.now()),state)==1;
    }
    public String execute(SlackCommand command,String requestId) {
        var actor=accounts.actor(command);var channel=accounts.require(command,actor);
        var args=SlackCommand.parse(command.text());var project=channel.projectId();
        return switch(args.action()) {
            case HOME -> home(project,actor);
            case SPEC -> {
                var snapshot=snapshots.latest(project,actor);
                yield "*"+projectName(project)+"* 기준 명세 revision "+snapshot.revision()+"\n"+projectLink(project);
            }
            case PENDING -> pending(project,actor);
            case REVIEW -> {
                var target=resolveProposal(project,actor,args);
                if(target==null) yield "검토를 기다리는 변경안이 없습니다. `/timiroom 대기`로 목록을 확인하세요.";
                var job=tasks.beginArtifactReview(project,actor,target.id(),target.revision(),"slack:"+requestId);
                yield "문서 교차검증을 시작했습니다 (작업 `"+shortId(job.jobId())+"`). 끝나면 이 채널에 알립니다.\n"+reviewLink(project,target.id());
            }
            case PR -> pullRequest(project,actor,args,requestId);
            case STATUS -> status(project,actor,args);
            default -> HELP;
        };
    }
    private record Target(UUID id,int revision) {}
    private static String shortId(UUID id) { return id.toString().substring(0,8); }
    String projectName(Long project) {
        return jdbc.query("select project_name from project where project_id=?",(rs,n)->rs.getString(1),project).stream().findFirst().orElse("프로젝트 "+project);
    }
    /** PM이 명령을 친 채널을 프로젝트 알림 채널로 연결한다. PM인 프로젝트가 하나면 이름을 생략할 수 있다. */
    private String linkChannel(SlackCommand command,Long actor,String query) {
        var projects=jdbc.queryForList("select p.project_id,p.project_name from project p join project_member m on m.project_id=p.project_id "
            +"where m.member_id=? and m.project_role='PM' and p.deleted_at is null order by p.project_id",actor);
        if(projects.isEmpty()) return "PM으로 있는 프로젝트가 없습니다. 채널 연결은 프로젝트 PM만 할 수 있습니다.";
        var matched=query==null?projects:projects.stream()
            .filter(row->String.valueOf(row.get("project_name")).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        if(matched.size()!=1) {
            String names=projects.stream().map(row->"• "+row.get("project_name")).collect(Collectors.joining("\n"));
            return (matched.isEmpty()?"이름이 맞는 프로젝트가 없습니다.":"어느 프로젝트인지 골라 주세요.")
                +" `/timiroom 채널연결 <프로젝트 이름>`으로 실행하세요.\n"+names;
        }
        Long project=((Number)matched.getFirst().get("project_id")).longValue();
        var existing=jdbc.queryForList("select project_id from slack_project_channel where team_id=? and channel_id=? and project_id<>?",
            command.team(),command.channel(),project);
        if(!existing.isEmpty())
            return "이 채널은 이미 '"+projectName(((Number)existing.getFirst().get("project_id")).longValue())+"' 프로젝트와 연결되어 있습니다. 먼저 `/timiroom 연결해제`를 실행하세요.";
        accounts.configure(project,actor,command.channel());
        return "이 채널을 '"+matched.getFirst().get("project_name")+"' 알림 채널로 연결했습니다. 알림을 받으려면 이 채널에 티미룸 앱을 초대하세요 (`/invite @앱이름`).\n`/timiroom`으로 현황을 볼 수 있습니다.";
    }
    private static final Map<String,String> EVENT_WORDS=Map.ofEntries(
        Map.entry("변경안","CHANGE"),Map.entry("승인","CHANGE"),Map.entry("change","CHANGE"),
        Map.entry("교차검증","REVIEW"),Map.entry("검증","REVIEW"),Map.entry("review","REVIEW"),
        Map.entry("pr","PR"),Map.entry("PR","PR"));
    private static final Map<String,String> EVENT_LABELS=Map.of("CHANGE","변경안 승인 요청","REVIEW","교차검증 결과","PR","PR 검사 결과");
    private static String eventLabels(String events) {
        return Arrays.stream(events.split(",")).map(e->EVENT_LABELS.getOrDefault(e,e)).collect(Collectors.joining(", "));
    }
    /** 이 채널이 받을 알림 종류를 보거나 바꾼다. 바꾸는 것은 프로젝트 PM만 할 수 있다. */
    private String notifySettings(SlackCommand command,Long actor,String query) {
        var channel=accounts.channel(command.channel());
        if(query==null) return "이 채널은 '"+projectName(channel.projectId())+"'의 "+eventLabels(accounts.events(channel.projectId(),command.channel()))
            +" 알림을 받습니다.\n바꾸려면 `/timiroom 알림 변경안 교차검증 pr` 중 원하는 것만 적거나 `/timiroom 알림 전체`를 실행하세요.";
        var picked=new LinkedHashSet<String>();
        for(String word:query.split("\\s+")) {
            if(word.equals("전체") || word.equalsIgnoreCase("all")) { picked.addAll(SlackAccountService.EVENTS); continue; }
            String event=EVENT_WORDS.get(word.toLowerCase(Locale.ROOT).equals("pr")?"pr":word);
            if(event==null) return "알림 종류는 변경안, 교차검증, pr, 전체 중에서 고르세요.";
            picked.add(event);
        }
        accounts.configure(channel.projectId(),actor,command.channel(),picked);
        return "이제 이 채널은 "+eventLabels(SlackAccountService.normalizeEvents(picked))+" 알림만 받습니다.";
    }
    private List<Map<String,Object>> readyProposals(Long project,int limit) {
        return jdbc.queryForList("select proposal_id,snapshot_id,proposal_revision,documents_json,created_at from spec_change_proposal "
            +"where project_id=? and state='READY' order by created_at desc limit ?",project,limit);
    }
    private UUID latestSnapshot(Long project,Long actor) {
        try { return snapshots.latest(project,actor).snapshotId(); } catch(RuntimeException noPublishedSpec) { return null; }
    }
    private String pending(Long project,Long actor) {
        var rows=readyProposals(project,6);
        if(rows.isEmpty()) return "*"+projectName(project)+"* 승인을 기다리는 변경안이 없습니다.";
        UUID latest=latestSnapshot(project,actor);
        var lines=new ArrayList<String>();
        lines.add("*"+projectName(project)+"* 승인 대기 변경안 "+(rows.size()>5?"5개 이상":rows.size()+"개"));
        for(var row:rows.subList(0,Math.min(5,rows.size()))) {
            UUID id=(UUID)row.get("proposal_id");
            boolean stale=latest!=null && !latest.equals(row.get("snapshot_id"));
            lines.add("• `"+shortId(id)+"` "+documentLabels((String)row.get("documents_json"))
                +(stale?" · 기준이 바뀌어 승인할 수 없음":"")+" · <"+reviewLink(project,id)+"|열기>");
        }
        lines.add("교차검증: `/timiroom 검증 <ID 앞부분>` · 승인은 티미룸 화면에서 합니다.");
        return String.join("\n",lines);
    }
    private String documentLabels(String json) {
        if(json==null) return "문서";
        var labels=new ArrayList<String>();
        for(var entry:new TreeMap<>(DOC_LABELS).entrySet()) if(json.contains("\""+entry.getKey()+"\"")) labels.add(entry.getValue());
        return labels.isEmpty()?"문서":String.join("·",labels);
    }
    private Target resolveProposal(Long project,Long actor,SlackCommand.Arguments args) {
        if(args.id()!=null && args.revision()>0) return new Target(args.id(),args.revision());
        UUID latest=latestSnapshot(project,actor);
        for(var row:readyProposals(project,50)) {
            UUID id=(UUID)row.get("proposal_id");
            if(args.id()!=null && !args.id().equals(id)) continue;
            if(args.query()!=null && !id.toString().startsWith(args.query())) continue;
            // ID를 지정하지 않으면 기준이 바뀌어 승인할 수 없는 변경안은 건너뛴다.
            if(args.id()==null && args.query()==null && latest!=null && !latest.equals(row.get("snapshot_id"))) continue;
            return new Target(id,((Number)row.get("proposal_revision")).intValue());
        }
        return null;
    }
    private String pullRequest(Long project,Long actor,SlackCommand.Arguments args,String requestId) {
        UUID snapshot=args.id();Long repo=args.repo();String head=args.head();
        if(head==null) {
            var candidates=pullRequests.list(project,actor).stream()
                .filter(pr->pr.number()==args.pull())
                .filter(pr->args.query()==null || pr.repoFullName().toLowerCase(Locale.ROOT).contains(args.query().toLowerCase(Locale.ROOT)))
                .toList();
            if(candidates.isEmpty()) return "연결된 레포에서 열린 PR #"+args.pull()+"을 찾지 못했습니다.";
            if(candidates.size()>1) return "PR #"+args.pull()+"이 여러 레포에 있습니다. `/timiroom pr <레포 이름> "+args.pull()+"`으로 고르세요.\n"
                +candidates.stream().map(pr->"• "+pr.repoFullName()).collect(Collectors.joining("\n"));
            var pr=candidates.getFirst();repo=pr.repoId();head=pr.headSha();
            snapshot=latestSnapshot(project,actor);
            if(snapshot==null) return "아직 기준으로 발행한 명세가 없습니다. 티미룸에서 기준 명세를 먼저 발행하세요.";
        }
        var job=tasks.beginPullRequestReview(project,actor,snapshot,repo,args.pull(),head,"slack:"+requestId);
        return "PR #"+args.pull()+" 정합성 검사를 시작했습니다 (작업 `"+shortId(job.jobId())+"`). 끝나면 이 채널에 알립니다.\n"+jobLink(project,job.jobId());
    }
    private String status(Long project,Long actor,SlackCommand.Arguments args) {
        if(args.id()!=null) {
            var job=jobs.get(project,actor,args.id());
            return JOB_LABELS.getOrDefault(job.kind().name(),job.kind().name())+" · "
                +STATE_LABELS.getOrDefault(job.status().name(),job.status().name())+"\n"+jobLink(project,job.jobId());
        }
        var rows=args.query()!=null
            ? jdbc.queryForList("select job_id,kind,state,result_json from integration_job where project_id=? and job_id::text like ? order by created_at desc limit 5",project,args.query()+"%")
            : jdbc.queryForList("select job_id,kind,state,result_json from integration_job where project_id=? order by created_at desc limit 5",project);
        if(rows.isEmpty()) return args.query()!=null?"그 ID로 시작하는 작업이 없습니다.":"아직 실행한 작업이 없습니다.";
        var lines=new ArrayList<String>();lines.add("*"+projectName(project)+"* 최근 작업");
        for(var row:rows) {
            UUID id=(UUID)row.get("job_id");
            lines.add("• `"+shortId(id)+"` "+JOB_LABELS.getOrDefault((String)row.get("kind"),(String)row.get("kind"))
                +" · "+outcome((String)row.get("state"),(String)row.get("result_json"))+" · <"+jobLink(project,id)+"|보기>");
        }
        return String.join("\n",lines);
    }
    private static String outcome(String state,String result) {
        String compact=result==null?"":result.replace(" ","");
        if("COMPLETED".equals(state) && compact.contains("\"passed\":true")) return "통과";
        if("COMPLETED".equals(state) && compact.contains("\"passed\":false")) return "확인 필요";
        return STATE_LABELS.getOrDefault(state,state);
    }
    private String home(Long project,Long actor) {
        UUID latest=latestSnapshot(project,actor);
        String spec=latest==null?"기준 명세 없음":"기준 명세 revision "+snapshots.latest(project,actor).revision();
        long ready=readyProposals(project,50).stream().filter(row->latest==null || latest.equals(row.get("snapshot_id"))).count();
        return "*"+projectName(project)+"* · "+spec+" · 승인 대기 "+ready+"개\n"
            +(ready>0?"`/timiroom 대기`로 목록을 보고 `/timiroom 검증`으로 교차검증을 돌릴 수 있습니다.\n":"")
            +"`/timiroom 도움말`로 전체 명령을 볼 수 있습니다.\n"+projectLink(project);
    }
    private Map<String,String> privateResponse(String text) { return Map.of("response_type","ephemeral","text",text); }
}
