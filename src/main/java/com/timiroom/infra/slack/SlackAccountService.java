package com.timiroom.infra.slack;

import com.timiroom.domain.spec.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
@ConditionalOnProperty(name={"integration.enabled","integration.slack.enabled"},havingValue="true")
public class SlackAccountService {
    private final JdbcTemplate jdbc;
    private final DocumentAccessService access;
    @Value("${integration.slack.team-id}") private String team;
    public record Channel(Long projectId,String teamId,String channelId,Long configuredBy,Instant configuredAt) {}
    /** 채널마다 고를 수 있는 알림 종류: 변경안 승인 요청, 교차검증 결과, PR 검사 결과 */
    public static final List<String> EVENTS=List.of("CHANGE","REVIEW","PR");
    public static String normalizeEvents(Collection<String> events) {
        if(events==null || events.isEmpty()) return String.join(",",EVENTS);
        var picked=EVENTS.stream().filter(events::contains).toList();
        if(picked.size()!=new HashSet<>(events).size()) throw new IllegalArgumentException("SLACK_EVENTS_INVALID");
        return String.join(",",picked);
    }
    @Transactional
    public String issueCode(SlackCommand command) {
        if(!team.equals(command.team())) throw new SecurityException("ACCESS_DENIED");
        // Serialize issuance for a Slack identity even before it has an account link.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,team+":"+command.user());
        var now=Instant.now();
        int count=jdbc.queryForObject("select count(*) from slack_link_code where team_id=? and user_id=? and created_at>?",Integer.class,
            team,command.user(),Timestamp.from(now.minusSeconds(60)));
        if(count>=3) throw new IllegalStateException("RATE_LIMITED");
        byte[] entropy=new byte[18];new SecureRandom().nextBytes(entropy);
        String code=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        jdbc.update("insert into slack_link_code(code_hash,team_id,user_id,created_at,expires_at) values(?,?,?,?,?)",
            DocumentHash.of(code),team,command.user(),Timestamp.from(now),Timestamp.from(now.plusSeconds(300)));
        return code;
    }
    @Transactional
    public void link(Long member,String code) {
        if(member==null || code==null || !code.matches("[A-Za-z0-9_-]{24}")) throw new SecurityException("SLACK_LINK_INVALID");
        var rows=jdbc.queryForList("select * from slack_link_code where code_hash=? and team_id=? for update",DocumentHash.of(code),team);
        var now=Instant.now();
        if(rows.size()!=1 || rows.getFirst().get("consumed_at")!=null
            || !((Timestamp)rows.getFirst().get("expires_at")).toInstant().isAfter(now)) throw new SecurityException("SLACK_LINK_INVALID");
        String user=(String)rows.getFirst().get("user_id");
        linkIdentity(member,team,user);
        jdbc.update("update slack_link_code set consumed_at=? where code_hash=?",Timestamp.from(now),DocumentHash.of(code));
    }
    @Transactional
    public void linkIdentity(Long member,String identityTeam,String user) {
        if(member==null || !team.equals(identityTeam) || user==null || !user.matches("[UW][A-Z0-9]{1,63}"))
            throw new SecurityException("SLACK_IDENTITY_INVALID");
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,team+":"+user);
        var now=Instant.now();
        // No silent replacement of another member's or another Slack identity's link.
        var existing=jdbc.queryForList("select * from slack_account_link where team_id=? and (user_id=? or member_id=?)",team,user,member);
        if(!existing.isEmpty() && existing.stream().anyMatch(r->!user.equals(r.get("user_id")) || !member.equals(((Number)r.get("member_id")).longValue())))
            throw new IllegalStateException("SLACK_LINK_CONFLICT");
        jdbc.update("insert into slack_account_link(team_id,user_id,member_id,linked_at) values(?,?,?,?) on conflict(team_id,user_id) do nothing",
            team,user,member,Timestamp.from(now));
    }
    public Optional<Map<String,Object>> connection(Long member) {
        return jdbc.queryForList("select team_id,user_id,linked_at from slack_account_link where team_id=? and member_id=?",team,member).stream().findFirst();
    }
    @Transactional public void unlink(Long member) {
        jdbc.update("delete from slack_project_channel where team_id=? and configured_by=?",team,member);
        jdbc.update("delete from slack_account_link where team_id=? and member_id=?",team,member);
    }
    public Long actor(SlackCommand command) {
        if(!team.equals(command.team())) throw new SecurityException("ACCESS_DENIED");
        return jdbc.query("select member_id from slack_account_link where team_id=? and user_id=?",
            (rs,n)->rs.getLong(1),team,command.user()).stream().findFirst().orElseThrow(()->new SecurityException("SLACK_ACCOUNT_NOT_LINKED"));
    }
    public Channel channel(String channel) {
        return jdbc.query("select * from slack_project_channel where team_id=? and channel_id=?",(rs,n)->new Channel(
            rs.getLong("project_id"),rs.getString("team_id"),rs.getString("channel_id"),rs.getLong("configured_by"),rs.getTimestamp("configured_at").toInstant()),
            team,channel).stream().findFirst().orElseThrow(()->new SecurityException("SLACK_CHANNEL_NOT_LINKED"));
    }
    public Channel require(SlackCommand command,Long actor) {
        var channel=channel(command.channel());access.requireRead(channel.projectId(),actor);
        access.requirePm(channel.projectId(),channel.configuredBy());return channel;
    }
    @Transactional public void configure(Long project,Long member,String channel) { configure(project,member,channel,null); }
    /** 프로젝트에 채널을 추가하거나, 이미 연결된 채널이면 받을 알림 종류만 바꾼다. 프로젝트 하나에 채널 여러 개를 둘 수 있다. */
    @Transactional public void configure(Long project,Long member,String channel,Collection<String> events) {
        access.requirePm(project,member);
        if(connection(member).isEmpty()) throw new SecurityException("SLACK_ACCOUNT_NOT_LINKED");
        if(channel==null || !channel.matches("[CG][A-Z0-9]{1,63}")) throw new IllegalArgumentException("SLACK_CHANNEL_INVALID");
        String picked=normalizeEvents(events);
        var other=jdbc.queryForList("select project_id from slack_project_channel where team_id=? and channel_id=? and project_id<>?",team,channel,project);
        if(!other.isEmpty()) throw new IllegalStateException("SLACK_CHANNEL_IN_USE");
        var now=Timestamp.from(Instant.now());
        int updated=jdbc.update("update slack_project_channel set events=? where project_id=? and channel_id=? and team_id=?",picked,project,channel,team);
        if(updated==0) jdbc.update("insert into slack_project_channel(project_id,team_id,channel_id,configured_by,configured_at,events) values(?,?,?,?,?,?)",
            project,team,channel,member,now,picked);
    }
    /** 내가 PM인 프로젝트에 연결된 채널(다른 PM이 연결한 것 포함) */
    public List<Map<String,Object>> channels(Long member) {
        try {
            return jdbc.queryForList("select c.project_id,c.channel_id,c.events,c.configured_at from slack_project_channel c "
                +"where c.team_id=? and (c.configured_by=? or exists(select 1 from project_member m where m.project_id=c.project_id and m.member_id=? and m.project_role='PM')) "
                +"order by c.project_id,c.configured_at",team,member,member);
        } catch(org.springframework.dao.DataAccessException noMemberTable) {
            // 프로젝트 표가 없는 슬랙 전용 테스트 스키마
            return jdbc.queryForList("select project_id,channel_id,events,configured_at from slack_project_channel where team_id=? and configured_by=?",team,member);
        }
    }
    public String events(Long project,String channel) {
        return jdbc.query("select events from slack_project_channel where team_id=? and project_id=? and channel_id=?",(rs,n)->rs.getString(1),team,project,channel)
            .stream().findFirst().orElse(String.join(",",EVENTS));
    }
    @Transactional public void removeChannel(Long project,Long member) {
        access.requirePm(project,member);jdbc.update("delete from slack_project_channel where project_id=? and team_id=?",project,team);
    }
    @Transactional public void removeChannel(Long project,Long member,String channel) {
        access.requirePm(project,member);jdbc.update("delete from slack_project_channel where project_id=? and team_id=? and channel_id=?",project,team,channel);
    }
}
