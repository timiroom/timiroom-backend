package com.timiroom.infra.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class SlackOidcClientTest {
    Jwt token(String nonce,String team,String subject) {
        return Jwt.withTokenValue("test").header("alg","RS256").subject(subject)
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
            .claim("nonce",nonce).claim("https://slack.com/team_id",team)
            .claim("https://slack.com/user_id","UTEST").build();
    }
    @Test void identityRequiresNonceWorkspaceAndMatchingSubject() {
        assertThat(SlackOidcClient.validateIdentity(token("nonce","TTEST","UTEST"),"nonce","TTEST"))
            .isEqualTo(new SlackOidcClient.Identity("TTEST","UTEST"));
        assertThatThrownBy(()->SlackOidcClient.validateIdentity(token("wrong","TTEST","UTEST"),"nonce","TTEST")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->SlackOidcClient.validateIdentity(token("nonce","TOTHER","UTEST"),"nonce","TTEST")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->SlackOidcClient.validateIdentity(token("nonce","TTEST","UOTHER"),"nonce","TTEST")).isInstanceOf(SecurityException.class);
    }
    @Test void authorizationRequestsOnlyIdentityAndNeverExposesSecret() {
        var client=new SlackOidcClient("123.456","server-only-secret","TTEST","https://api.example.com",new ObjectMapper());
        assertThat(client.authorizationUrl("state","nonce")).startsWith("https://slack.com/openid/connect/authorize?")
            .contains("scope=openid","response_mode=form_post","state=state","nonce=nonce")
            .doesNotContain("server-only-secret","chat:write");
    }
}
