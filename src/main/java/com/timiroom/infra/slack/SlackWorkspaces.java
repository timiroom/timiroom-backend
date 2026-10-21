package com.timiroom.infra.slack;

import com.timiroom.domain.aikey.service.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * 티미룸 앱이 설치된 Slack 워크스페이스와 각 워크스페이스의 봇 토큰.
 * 설정값(integration.slack.team-id / bot-token) 워크스페이스는 예전처럼 계속 쓰고,
 * "Slack에 앱 추가"로 설치한 워크스페이스는 slack_workspace 표에 암호화해 둔다.
 */
@Component
@ConditionalOnProperty(name={"integration.enabled","integration.slack.enabled"},havingValue="true")
public class SlackWorkspaces {
    public record Workspace(String teamId,String teamName,boolean configured) {}
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final String configuredTeam,configuredToken;

    public SlackWorkspaces(JdbcTemplate jdbc,SecretCipher cipher,
            @Value("${integration.slack.team-id:}") String configuredTeam,@Value("${integration.slack.bot-token:}") String configuredToken) {
        this.jdbc=jdbc;this.cipher=cipher;this.configuredTeam=configuredTeam;this.configuredToken=configuredToken;
    }
    /** 이 워크스페이스에서 온 명령·로그인을 받아도 되는지 */
    public boolean allowed(String team) {
        if(team==null || team.isBlank()) return false;
        if(team.equals(configuredTeam)) return true;
        return !jdbc.queryForList("select 1 from slack_workspace where team_id=? and revoked_at is null",team).isEmpty();
    }
    /** 이 워크스페이스로 메시지를 보낼 봇 토큰 */
    public String token(String team) {
        if(team==null || team.isBlank() || team.equals(configuredTeam)) {
            if(configuredToken.isBlank()) throw new IllegalStateException("SLACK_WORKSPACE_NOT_INSTALLED");
            return configuredToken;
        }
        return jdbc.query("select bot_token_enc from slack_workspace where team_id=? and revoked_at is null",(rs,n)->rs.getString(1),team)
            .stream().findFirst().map(cipher::decrypt).orElseThrow(()->new IllegalStateException("SLACK_WORKSPACE_NOT_INSTALLED"));
    }
    public List<Workspace> list() {
        var result=new ArrayList<Workspace>();
        if(!configuredTeam.isBlank()) result.add(new Workspace(configuredTeam,null,true));
        jdbc.query("select team_id,team_name from slack_workspace where revoked_at is null order by installed_at",(rs,n)->{
            if(!rs.getString(1).equals(configuredTeam)) result.add(new Workspace(rs.getString(1),rs.getString(2),false));return null;});
        return result;
    }
    /** "Slack에 앱 추가"를 마친 워크스페이스를 저장한다(다시 설치하면 토큰을 바꾼다). */
    public void install(String team,String teamName,String botToken,String botUserId,Long member) {
        if(team==null || !team.matches("T[A-Z0-9]{1,63}") || botToken==null || !botToken.startsWith("xoxb-"))
            throw new IllegalArgumentException("SLACK_INSTALL_INVALID");
        String enc=cipher.encrypt(botToken);var now=Timestamp.from(Instant.now());
        int updated=jdbc.update("update slack_workspace set team_name=?,bot_token_enc=?,bot_user_id=?,installed_by=?,installed_at=?,revoked_at=null where team_id=?",
            teamName,enc,botUserId,member,now,team);
        if(updated==0) jdbc.update("insert into slack_workspace(team_id,team_name,bot_token_enc,bot_user_id,installed_by,installed_at) values(?,?,?,?,?,?)",
            team,teamName,enc,botUserId,member,now);
    }
}
