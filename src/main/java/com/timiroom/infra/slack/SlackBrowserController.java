package com.timiroom.infra.slack;

import com.timiroom.domain.integration.service.IntegrationActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import java.util.Map;

@RestController @RequiredArgsConstructor
@ConditionalOnProperty(name={"integration.enabled","integration.slack.enabled"},havingValue="true")
public class SlackBrowserController {
    private final SlackBrowserConnection flow;
    private final IntegrationActorResolver actors;
    @Value("${frontend.url}") private String frontend;

    @PostMapping("/api/v1/integrations/slack/connect")
    public ResponseEntity<?> start(Authentication auth,HttpServletRequest request) {
        try {return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Map.of("url",flow.start(actors.memberId(auth),request.getSession())));}
        catch(IllegalStateException unavailable) {return ResponseEntity.status(503)
            .body(Map.of("code","SLACK_CONNECT_UNAVAILABLE"));}
    }
    // Slack can return a query callback even when form_post was requested.
    @GetMapping("/integrations/slack/oauth/callback")
    public ResponseEntity<Void> queryCallback(@RequestParam(required=false) String state,
            @RequestParam(required=false) String code,@RequestParam(required=false) String error) {
        return callback(state,code,error);
    }
    // Slack form_post is cross-site: do not create/replace a session here. A top-level
    // GET restores the existing SameSite=Lax cookie before validating its state.
    @PostMapping(value="/integrations/slack/oauth/callback",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> callback(@RequestParam(required=false) String state,
            @RequestParam(required=false) String code,@RequestParam(required=false) String error) {
        if(state==null || !state.matches("[A-Za-z0-9_-]{43}") || (code!=null && code.length()>4096))
            return redirect(frontend+"/mypage?slack=failed");
        var target=UriComponentsBuilder.fromPath("/integrations/slack/oauth/complete").queryParam("state",state);
        if(error!=null) target.queryParam("error","access_denied".equals(error)?"access_denied":"failed");
        else if(code!=null) target.queryParam("code",code);
        return redirect(target.build().encode().toUriString());
    }
    @GetMapping("/integrations/slack/oauth/complete")
    public ResponseEntity<Void> complete(Authentication auth,HttpServletRequest request,
            @RequestParam(required=false) String state,@RequestParam(required=false) String code,
            @RequestParam(required=false) String error) {
        String result=flow.complete(actors.memberId(auth),request.getSession(false),state,code,error);
        return redirect(frontend+"/mypage?slack="+result);
    }
    private ResponseEntity<Void> redirect(String target) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(target))
            .cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").build();
    }
}
