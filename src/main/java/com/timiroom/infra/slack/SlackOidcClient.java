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
    private final String clientId,secret,team,redirect;
    private final ObjectMapper mapper;
    private final JwtDecoder decoder;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public SlackOidcClient(@Value("${integration.slack.client-id:}") String clientId,
            @Value("${integration.slack.client-secret:}") String secret,
            @Value("${integration.slack.team-id}") String team,
            @Value("${integration.issuer:http://localhost:8080}") String issuer,ObjectMapper mapper) {
        this.clientId=clientId;this.secret=secret;this.team=team;
        this.redirect=issuer+"/integrations/slack/oauth/callback";this.mapper=mapper;
        var jwt=NimbusJwtDecoder.withJwkSetUri("https://slack.com/openid/connect/keys").build();
        jwt.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("https://slack.com"),
            new JwtClaimValidator<List<String>>("aud",aud->aud!=null && aud.contains(clientId))));
        this.decoder=jwt;
    }
    public boolean ready() {return !clientId.isBlank() && !secret.isBlank();}
    public String authorizationUrl(String state,String nonce) {
        if(!ready()) throw new IllegalStateException("SLACK_CONNECT_UNAVAILABLE");
        return UriComponentsBuilder.fromUriString("https://slack.com/openid/connect/authorize")
            .queryParam("response_type","code").queryParam("response_mode","form_post")
            .queryParam("client_id",clientId).queryParam("scope","openid")
            .queryParam("team",team).queryParam("redirect_uri",redirect)
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
            return validateIdentity(decoder.decode(json.path("id_token").asText()),nonce,team);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();throw new IllegalStateException("SLACK_CONNECT_FAILED");
        } catch(Exception e) {throw new IllegalStateException("SLACK_CONNECT_FAILED");}
    }
    static Identity validateIdentity(Jwt jwt,String nonce,String team) {
        String user=jwt.getClaimAsString("https://slack.com/user_id");
        if(jwt.getExpiresAt()==null || jwt.getIssuedAt()==null || nonce==null || !nonce.equals(jwt.getClaimAsString("nonce"))
                || !team.equals(jwt.getClaimAsString("https://slack.com/team_id"))
                || user==null || !user.matches("[UW][A-Z0-9]{1,63}") || !user.equals(jwt.getSubject()))
            throw new SecurityException("SLACK_IDENTITY_INVALID");
        return new Identity(team,user);
    }
    private static String encode(String value) {return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
