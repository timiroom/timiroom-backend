package com.timiroom.domain.spec;

import com.timiroom.infra.slack.SlackRequestVerifier;
import com.timiroom.infra.slack.SlackCommand;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class SlackRequestTest {
    private static final String SECRET="8f742231b10e8888abcd99yyyzzz85a5";
    // Slack's published signature fixture; body must not be decoded or reordered.
    private static final String BODY="token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3J&team_domain=testteamnow&channel_id=G8PSS9T3V&channel_name=foobar&user_id=U2CERLKJA&user_name=roadrunner&command=%2Fwebhook-collect&text=&response_url=https%3A%2F%2Fhooks.slack.com%2Fcommands%2FT1DC2JH3J%2F397700885554%2F96rGlfmibIGlgcZRskXaIFfN&trigger_id=398738663015.47445629121.803a0bc887a14d10d2c447fce8b6703c";
    private SlackRequestVerifier verifier() { return new SlackRequestVerifier(SECRET,Clock.fixed(Instant.ofEpochSecond(1531420618),ZoneOffset.UTC)); }
    @Test void validatesOfficialRawBodyFixtureAndRejectsTampering() {
        String signature="v0=a2114d57b48eac39b9ad189dd8316235a7b4a8d21a10bd27519666489c69b503";
        assertThatCode(()->verifier().verify("1531420618",signature,BODY)).doesNotThrowAnyException();
        assertThatThrownBy(()->verifier().verify("1531420618",signature,BODY+" ")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->verifier().verify("1531420217",signature,BODY)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->verifier().verify("9223372036854775807",signature,BODY)).isInstanceOf(SecurityException.class);
    }
    @Test void duplicateIdentityParametersCannotOverrideVerifiedWorkspace() {
        String body="team_id=T1&api_app_id=A1&user_id=U1&channel_id=C1&command=%2Ftimiroom&text=spec";
        assertThat(SlackCommand.from(body,"T1","A1").text()).isEqualTo("spec");
        assertThatThrownBy(()->SlackCommand.from(body+"&team_id=T2","T1","A1")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->SlackCommand.from(body,"T2","A1")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->SlackCommand.from(body,"T1","A2")).isInstanceOf(SecurityException.class);
    }
    @Test void parsesOnlyExplicitReadAndReviewCommands() {
        assertThat(SlackCommand.parse("spec").action()).isEqualTo(SlackCommand.Action.SPEC);
        assertThat(SlackCommand.parse("review 12345678-1234-1234-1234-123456789abc 2").revision()).isEqualTo(2);
        assertThatThrownBy(()->SlackCommand.parse("approve 12345678-1234-1234-1234-123456789abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SlackCommand.parse("pr 12345678-1234-1234-1234-123456789abc 1 3 "+"a".repeat(41))).isInstanceOf(IllegalArgumentException.class);
    }
}
