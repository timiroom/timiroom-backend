package com.timiroom.infra.slack;

import com.timiroom.domain.integration.service.IntegrationActorResolver;
import com.timiroom.domain.spec.service.DocumentHash;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor
@ConditionalOnProperty(name={"integration.enabled","integration.slack.enabled"},havingValue="true")
public class SlackController {
    private final SlackRequestVerifier verifier;
    private final SlackCommandService commands;
    private final SlackAccountService accounts;
    private final IntegrationActorResolver actors;
    private final SlackOidcClient oidc;
    @Value("${integration.slack.team-id}") private String team;
    @Value("${integration.slack.app-id}") private String app;
    @PostMapping(value="/integrations/slack/commands",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String,String> command(@RequestHeader("X-Slack-Request-Timestamp") String timestamp,
        @RequestHeader("X-Slack-Signature") String signature,@RequestBody String body) {
        verifier.verify(timestamp,signature,body);
        var request=SlackCommand.from(body,team,app);
        try {return commands.accept(request,DocumentHash.of(timestamp+":"+body));}
        catch(SecurityException|IllegalArgumentException|IllegalStateException failure) {
            return Map.of("response_type","ephemeral","text",SlackCommandService.friendly(failure));
        }
    }
    @GetMapping("/api/v1/integrations/slack")
    public Map<String,Object> connection(Authentication auth) {
        Long member=actors.memberId(auth);
        return Map.of("connected",accounts.connection(member).isPresent(),"account",accounts.connection(member).orElse(Map.of()),
            "channels",accounts.channels(member),"connectAvailable",oidc.ready());
    }
    public record LinkRequest(String code) {}
    @PostMapping("/api/v1/integrations/slack/link")
    public ResponseEntity<Void> link(Authentication auth,@RequestBody LinkRequest input) {
        accounts.link(actors.memberId(auth),input.code());return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/api/v1/integrations/slack/link")
    public ResponseEntity<Void> unlink(Authentication auth) {
        accounts.unlink(actors.memberId(auth));return ResponseEntity.noContent().build();
    }
    /** events를 비우면 모든 알림(CHANGE·REVIEW·PR)을 받는다. 같은 채널을 다시 보내면 받을 알림만 바뀐다. */
    public record ChannelRequest(Long projectId,String channelId,java.util.List<String> events) {}
    @PostMapping("/api/v1/integrations/slack/channels")
    public ResponseEntity<Void> configure(Authentication auth,@RequestBody ChannelRequest input) {
        accounts.configure(input.projectId(),actors.memberId(auth),input.channelId(),input.events());return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/api/v1/integrations/slack/channels/{project}")
    public ResponseEntity<Void> remove(Authentication auth,@PathVariable Long project,@RequestParam(required=false) String channelId) {
        if(channelId==null) accounts.removeChannel(project,actors.memberId(auth));
        else accounts.removeChannel(project,actors.memberId(auth),channelId);
        return ResponseEntity.noContent().build();
    }
    @ExceptionHandler(SecurityException.class) public ResponseEntity<?> denied() {
        return ResponseEntity.status(403).body(Map.of("code","ACCESS_DENIED"));
    }
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("code","INVALID_INPUT")); }
    @ExceptionHandler({IllegalStateException.class,org.springframework.dao.DataIntegrityViolationException.class})
    public ResponseEntity<?> conflict() { return ResponseEntity.status(409).body(Map.of("code","SLACK_REQUEST_CONFLICT")); }
}
