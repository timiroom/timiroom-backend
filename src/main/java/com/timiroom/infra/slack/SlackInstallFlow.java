package com.timiroom.infra.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import java.io.Serializable;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * "Slack에 앱 추가": 워크스페이스에 티미룸 앱을 설치하고 그 워크스페이스의 봇 토큰을 받는다(Slack OAuth v2).
 * 설치한 사람의 Slack 계정도 그 자리에서 티미룸 계정에 연결한다.
 * Slack 앱 설정에서 배포(Manage Distribution)를 켜고 리디렉트 URL({issuer}/integrations/slack/install/callback)을 등록해야 한다.
 */
@Service
@ConditionalOnProperty(name={"integration.enabled","integration.slack.enabled"},havingValue="true")
public class SlackInstallFlow {
    static final String KEY=SlackInstallFlow.class.getName()+".pending";
    static final String BOT_SCOPES="commands,chat:write";
    record Pending(Long member,String state,Instant expires) implements Serializable {}
    public record Installed(String team,String teamName,String botToken,String botUser,String installer) {}

    private final String clientId,secret,app,redirect;
    private final ObjectMapper mapper;
    private final SlackWorkspaces workspaces;
    private final SlackAccountService accounts;
    private final Clock clock;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    public SlackInstallFlow(@Value("${integration.slack.client-id:}") String clientId,@Value("${integration.slack.client-secret:}") String secret,
            @Value("${integration.slack.app-id:}") String app,@Value("${integration.issuer:http://localhost:8080}") String issuer,
            ObjectMapper mapper,SlackWorkspaces workspaces,SlackAccountService accounts,Clock clock) {
        this.clientId=clientId;this.secret=secret;this.app=app;this.redirect=issuer+"/integrations/slack/install/callback";
        this.mapper=mapper;this.workspaces=workspaces;this.accounts=accounts;this.clock=clock;
    }
    public boolean ready() {return !clientId.isBlank() && !secret.isBlank();}

    public String start(Long member,HttpSession session) {
        if(!ready()) throw new IllegalStateException("SLACK_INSTALL_UNAVAILABLE");
        var pending=new Pending(member,random(),clock.instant().plusSeconds(600));
        session.setAttribute(KEY,pending);
        return UriComponentsBuilder.fromUriString("https://slack.com/oauth/v2/authorize")
            .queryParam("client_id",clientId).queryParam("scope",BOT_SCOPES).queryParam("redirect_uri",redirect)
            .queryParam("state",pending.state()).build().encode().toUriString();
    }

    /** @return connected | cancelled | failed */
    public String complete(Long member,HttpSession session,String state,String code,String error) {
        if(session==null) return "failed";
        Pending pending;
        synchronized(session) {
            pending=(Pending)session.getAttribute(KEY);
            if(pending==null || !pending.member().equals(member) || !pending.state().equals(state)) return "failed";
            session.removeAttribute(KEY);
        }
        if(!pending.expires().isAfter(clock.instant())) return "failed";
        if(error!=null) return "access_denied".equals(error)?"cancelled":"failed";
        if(code==null || code.isBlank() || code.length()>4096) return "failed";
        try {
            var installed=exchange(code);
            workspaces.install(installed.team(),installed.teamName(),installed.botToken(),installed.botUser(),member);
            // 설치한 사람의 Slack 계정은 바로 연결한다(이미 다른 계정과 연결돼 있으면 설치만 남긴다)
            try {accounts.linkIdentity(member,installed.team(),installed.installer());} catch(RuntimeException alreadyLinked) {/* 설치는 유지 */}
            return "installed";
        } catch(RuntimeException failure) {return "failed";}
    }

    Installed exchange(String code) {
        try {
            String body=Map.of("client_id",clientId,"client_secret",secret,"code",code,"redirect_uri",redirect).entrySet().stream()
                .map(e->enc(e.getKey())+"="+enc(e.getValue())).collect(Collectors.joining("&"));
            var request=HttpRequest.newBuilder(URI.create("https://slack.com/api/oauth.v2.access")).timeout(Duration.ofSeconds(8))
                .header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200 || response.body().length()>64000) throw new IllegalStateException("SLACK_INSTALL_FAILED");
            return parse(mapper.readTree(response.body()),app);
        } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException("SLACK_INSTALL_FAILED");}
          catch(IllegalStateException e) {throw e;}
          catch(Exception e) {throw new IllegalStateException("SLACK_INSTALL_FAILED");}
    }
    /** oauth.v2.access 응답 검증: 우리 앱의 봇 토큰이고, 워크스페이스·설치자 ID 형식이 맞아야 한다. */
    static Installed parse(com.fasterxml.jackson.databind.JsonNode json,String expectedApp) {
        String team=json.path("team").path("id").asText(""),token=json.path("access_token").asText(""),
            user=json.path("authed_user").path("id").asText(""),appId=json.path("app_id").asText("");
        if(!json.path("ok").asBoolean() || !"bot".equals(json.path("token_type").asText()) || !token.startsWith("xoxb-")
                || !team.matches("T[A-Z0-9]{1,63}") || !user.matches("[UW][A-Z0-9]{1,63}")
                || (!expectedApp.isBlank() && !expectedApp.equals(appId)))
            throw new IllegalStateException("SLACK_INSTALL_FAILED");
        String name=json.path("team").path("name").asText(null);
        return new Installed(team,name,token,json.path("bot_user_id").asText(null),user);
    }
    private static String enc(String v) {return URLEncoder.encode(v,StandardCharsets.UTF_8);}
    private static String random() {byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
}
