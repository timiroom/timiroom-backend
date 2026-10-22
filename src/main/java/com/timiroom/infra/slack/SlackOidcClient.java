package com.timiroom.infra.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
@ConditionalOnProperty(name={"integration.enabled","integration.slack.enabled"},havingValue="true")
public class SlackOidcClient {
    public record Identity(String team,String user) {}
    private final String clientId,secret,redirect;
    /** 로그인을 받을 워크스페이스. fixedTeam은 하나뿐일 때 Slack 로그인 화면에서 고정할 워크스페이스(없으면 null). */
    private final java.util.function.Predicate<String> allowed;
    private final java.util.function.Supplier<String> fixedTeam;
    private final ObjectMapper mapper;
    private final JwtDecoder decoder;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public SlackOidcClient(String clientId,String secret,String team,String issuer,ObjectMapper mapper) {
        this(clientId,secret,t->Objects.equals(team,t),()->team,issuer,mapper);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public SlackOidcClient(@Value("${integration.slack.client-id:}") String clientId,
            @Value("${integration.slack.client-secret:}") String secret,
            SlackWorkspaces workspaces,
            @Value("${integration.issuer:http://localhost:8080}") String issuer,ObjectMapper mapper) {
        this(clientId,secret,workspaces::allowed,()->workspaces.multiple() || workspaces.configuredTeam().isBlank()?null:workspaces.configuredTeam(),issuer,mapper);
    }
    private SlackOidcClient(String clientId,String secret,java.util.function.Predicate<String> allowed,java.util.function.Supplier<String> fixedTeam,
            String issuer,ObjectMapper mapper) {
        this.clientId=clientId;this.secret=secret;this.allowed=allowed;this.fixedTeam=fixedTeam;
        this.redirect=issuer+"/integrations/slack/oauth/callback";this.mapper=mapper;
        var jwt=NimbusJwtDecoder.withJwkSetUri("https://slack.com/openid/connect/keys").build();
        jwt.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("https://slack.com"),
            new JwtClaimValidator<List<String>>("aud",aud->aud!=null && aud.contains(clientId))));
        this.decoder=jwt;
    }
    public boolean ready() {return !clientId.isBlank() && !secret.isBlank();}
    public String authorizationUrl(String state,String nonce) {
        if(!ready()) throw new IllegalStateException("SLACK_CONNECT_UNAVAILABLE");
        var url=UriComponentsBuilder.fromUriString("https://slack.com/openid/connect/authorize")
            .queryParam("response_type","code").queryParam("response_mode","form_post")
            .queryParam("client_id",clientId).queryParam("scope","openid");
        // 워크스페이스가 하나면 그 워크스페이스로 고정하고, 여러 개면 Slack에서 고르게 둔다
        String team=fixedTeam.get();
        if(team!=null) url.queryParam("team",team);
        return url.queryParam("redirect_uri",redirect)
            .queryParam("state",state).queryParam("nonce",nonce).build().encode().toUriString();
    }
    public Identity identity(String code,String nonce) {
        try {
            var params=Map.of("client_id",clientId,"client_secret",secret,"code",code,
                "redirect_uri",redirect,"grant_type","authorization_code");
            String body=params.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
            var request=HttpRequest.newBuilder(URI.create("https://slack.com/api/openid.connect.token"))
                .timeout(Duration.ofSeconds(8)).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200 || response.body().length()>64000) throw new SecurityException();
            var json=mapper.readTree(response.body());
            if(!json.path("ok").asBoolean()) throw new SecurityException();
            return validateIdentity(decoder.decode(json.path("id_token").asText()),nonce,allowed);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();throw new IllegalStateException("SLACK_CONNECT_FAILED");
        } catch(Exception e) {throw new IllegalStateException("SLACK_CONNECT_FAILED");}
    }
    static Identity validateIdentity(Jwt jwt,String nonce,String team) {
        return validateIdentity(jwt,nonce,t->Objects.equals(team,t));
    }
    static Identity validateIdentity(Jwt jwt,String nonce,java.util.function.Predicate<String> allowed) {
        String user=jwt.getClaimAsString("https://slack.com/user_id");
        String team=jwt.getClaimAsString("https://slack.com/team_id");
        if(jwt.getExpiresAt()==null || jwt.getIssuedAt()==null || nonce==null || !nonce.equals(jwt.getClaimAsString("nonce"))
                || team==null || !allowed.test(team)
                || user==null || !user.matches("[UW][A-Z0-9]{1,63}") || !user.equals(jwt.getSubject()))
            throw new SecurityException("SLACK_IDENTITY_INVALID");
        return new Identity(team,user);
    }
    private static String encode(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
