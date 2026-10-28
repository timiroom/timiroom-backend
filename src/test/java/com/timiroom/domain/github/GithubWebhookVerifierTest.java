package com.timiroom.domain.github;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class GithubWebhookVerifierTest {

    @Test
    void valid_sha256_signature만_통과시킨다() throws Exception {
        byte[] payload = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        GithubWebhookVerifier verifier = new GithubWebhookVerifier("webhook-secret");

        assertThat(verifier.verify(payload, signature("webhook-secret", payload))).isTrue();
        assertThat(verifier.verify(payload, "sha256=invalid")).isFalse();
        assertThat(new GithubWebhookVerifier("").verify(payload, "sha256=anything")).isFalse();
    }

    private String signature(String secret, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }
}
