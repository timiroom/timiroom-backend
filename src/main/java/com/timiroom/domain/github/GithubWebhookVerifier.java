package com.timiroom.domain.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** GitHub X-Hub-Signature-256 검증. 시크릿이 없으면 웹훅을 받지 않는다. */
@Component
public class GithubWebhookVerifier {

    private final String webhookSecret;

    public GithubWebhookVerifier(@Value("${github.webhook-secret:}") String webhookSecret) {
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret;
    }

    public boolean verify(byte[] payload, String signature) {
        if (webhookSecret.isBlank() || signature == null || !signature.startsWith("sha256=")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    signature.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException("GitHub webhook 서명 검증 실패", e);
        }
    }
}
