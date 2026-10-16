package com.timiroom.infra.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

@Component
@ConditionalOnProperty(name={"integration.enabled","integration.slack.enabled"},havingValue="true")
public class SlackWebClient {
    private final String token;
    private final ObjectMapper mapper;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public SlackWebClient(@Value("${integration.slack.bot-token}") String token,ObjectMapper mapper) {
        if(token==null || !token.startsWith("xoxb-") || token.contains("\n") || token.contains("\r"))
            throw new IllegalArgumentException("Slack bot token required");
        this.token=token;this.mapper=mapper;
    }
    public void notifyChannel(String channel,String text) {
        send("chat.postMessage",Map.of("channel",channel,"text",text,"unfurl_links",false,"unfurl_media",false,"mrkdwn",false,"parse","none"));
    }
    public void reply(String channel,String user,String text) {
        send("chat.postEphemeral",Map.of("channel",channel,"user",user,"text",text,"parse","none"));
    }
    private void send(String method,Map<String,Object> body) {
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
