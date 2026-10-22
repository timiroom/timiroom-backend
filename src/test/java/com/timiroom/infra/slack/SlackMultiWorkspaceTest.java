package com.timiroom.infra.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class SlackMultiWorkspaceTest {
    @Test void commandsAreAcceptedFromEveryInstalledWorkspaceAndKeepTheirTeam() {
        var installed=Set.of("T1","T9");
        String body="team_id=T9&api_app_id=A1&user_id=U1&channel_id=C1&command=%2Ftimiroom&text=spec";
        assertThat(SlackCommand.from(body,installed::contains,"A1").team()).isEqualTo("T9");
        assertThatThrownBy(()->SlackCommand.from(body.replace("T9","T2"),installed::contains,"A1")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->SlackCommand.from(body.replace("team_id=T9&",""),t->true,"A1")).isInstanceOf(SecurityException.class);
    }
    @Test void installAcceptsOnlyOurAppsBotToken() throws Exception {
        var mapper=new ObjectMapper();
        String ok="{\"ok\":true,\"token_type\":\"bot\",\"access_token\":\"xoxb-1\",\"app_id\":\"A1\",\"bot_user_id\":\"B1\","
            +"\"team\":{\"id\":\"T9\",\"name\":\"다른 팀\"},\"authed_user\":{\"id\":\"U9\"}}";
        var installed=SlackInstallFlow.parse(mapper.readTree(ok),"A1");
        assertThat(installed.team()).isEqualTo("T9");
        assertThat(installed.teamName()).isEqualTo("다른 팀");
        assertThat(installed.installer()).isEqualTo("U9");
        assertThatThrownBy(()->SlackInstallFlow.parse(mapper.readTree(ok),"A2")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SlackInstallFlow.parse(mapper.readTree(ok.replace("xoxb-1","xoxp-1")),"A1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->SlackInstallFlow.parse(mapper.readTree(ok.replace("\"ok\":true","\"ok\":false")),"A1")).isInstanceOf(IllegalStateException.class);
    }
    @Test void signInAcceptsAnyInstalledWorkspace() {
        var jwt=Jwt.withTokenValue("t").header("alg","RS256").subject("U1").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
            .claim("nonce","n").claim("https://slack.com/team_id","T9").claim("https://slack.com/user_id","U1").build();
        assertThat(SlackOidcClient.validateIdentity(jwt,"n",Set.of("T1","T9")::contains)).isEqualTo(new SlackOidcClient.Identity("T9","U1"));
        assertThatThrownBy(()->SlackOidcClient.validateIdentity(jwt,"n",Set.of("T1")::contains)).isInstanceOf(SecurityException.class);
    }
    @Test void webClientRefusesWorkspaceWithoutToken() {
        var client=new SlackWebClient("xoxb-test",new ObjectMapper());
        assertThat(client).isNotNull();
        assertThatThrownBy(()->new SlackWebClient("xoxp-user",new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
    }
}
