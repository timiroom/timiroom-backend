package com.timiroom.infra.portone;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class PortOneWebhookVerifierTest {

    private static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString("test-webhook-secret-32-bytes!!!!".getBytes());

    private PortOneWebhookVerifier verifier() {
        PortOneProperties properties = new PortOneProperties();
        properties.setWebhookSecret(SECRET);
        return new PortOneWebhookVerifier(properties);
    }

    private static String signature(String id, String ts, String body) {
        return "v1," + Base64.getEncoder().encodeToString(PortOneWebhookVerifier.sign(SECRET, id + "." + ts + "." + body));
    }

    @Test
    void 올바른_서명은_통과한다() {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"p1\"}}";
        assertThat(verifier().verify("msg_1", ts, "v1,wrong " + signature("msg_1", ts, body), body)).isTrue();
    }

    @Test
    void 본문이_바뀌거나_오래된_요청은_거절한다() {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"a\":1}";
        assertThat(verifier().verify("msg_1", ts, signature("msg_1", ts, body), "{\"a\":2}")).isFalse();
        String old = String.valueOf(Instant.now().minusSeconds(3600).getEpochSecond());
        assertThat(verifier().verify("msg_1", old, signature("msg_1", old, body), body)).isFalse();
    }

    @Test
    void 비밀키가_없으면_모두_거절한다() {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        assertThat(new PortOneWebhookVerifier(new PortOneProperties()).verify("m", ts, signature("m", ts, "{}"), "{}")).isFalse();
    }
}
