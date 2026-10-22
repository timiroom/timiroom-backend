package com.timiroom.infra.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

@Component
@ConditionalOnProperty(name={"integration.enabled","integration.slack.enabled"},havingValue="true")
public class SlackWebClient {
    private final java.util.function.Function<String,String> tokens;
    private final ObjectMapper mapper;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    /** 워크스페이스마다 그 워크스페이스에 설치된 봇 토큰으로 보낸다 */
    @org.springframework.beans.factory.annotation.Autowired
    public SlackWebClient(SlackWorkspaces workspaces,ObjectMapper mapper) { this.tokens=workspaces::token;this.mapper=mapper; }
    /** 워크스페이스 하나짜리(테스트·예전 설정) */
    public SlackWebClient(String token,ObjectMapper mapper) {
        if(!valid(token)) throw new IllegalArgumentException("Slack bot token required");
        this.tokens=team->token;this.mapper=mapper;
    }
    private static boolean valid(String token) {
        return token!=null && token.startsWith("xoxb-") && !token.contains("\n") && !token.contains("\r");
    }
    public void notifyChannel(String team,String channel,String text) {
        send(team,"chat.postMessage",Map.of("channel",channel,"text",text,"unfurl_links",false,"unfurl_media",false,"mrkdwn",true,"parse","none"));
    }
    public void reply(String team,String channel,String user,String text) {
        send(team,"chat.postEphemeral",Map.of("channel",channel,"user",user,"text",text,"parse","none"));
    }
    private void send(String team,String method,Map<String,Object> body) {
        String token=tokens.apply(team);
        if(!valid(token)) throw new IllegalStateException("SLACK_WORKSPACE_NOT_INSTALLED");
        try {
            var request=HttpRequest.newBuilder(URI.create("https://slack.com/api/"+method)).timeout(Duration.ofSeconds(5))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200 || response.body().length()>64000 || !mapper.readTree(response.body()).path("ok").asBoolean())
                throw new IllegalStateException("SLACK_DELIVERY_FAILED");
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();throw new IllegalStateException("SLACK_DELIVERY_UNKNOWN");
        } catch(Exception e) { throw new IllegalStateException("SLACK_DELIVERY_UNKNOWN"); }
    }
}
