package com.timiroom.infra.portone;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * PortOne V2 웹훅 서명 확인(Standard Webhooks 규격).
 * 서명 대상: "{webhook-id}.{webhook-timestamp}.{원문 본문}", HMAC-SHA256, 키는 "whsec_" 뒤 base64.
 * 헤더 webhook-signature에는 "v1,{base64}"가 공백으로 여러 개 올 수 있다.
 * 서명이 맞아도 본문 내용은 믿지 않고, 결제는 PortOne API로 다시 조회해 반영한다.
 */
@Component
public class PortOneWebhookVerifier {

    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    private final PortOneProperties properties;

    public PortOneWebhookVerifier(PortOneProperties properties) {
        this.properties = properties;
    }

    public boolean verify(String webhookId, String timestamp, String signatureHeader, String rawBody) {
        String secret = properties.getWebhookSecret();
        if (secret == null || secret.isBlank() || webhookId == null || timestamp == null || signatureHeader == null) {
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(seconds), Instant.now()).abs().compareTo(TOLERANCE) > 0) {
            return false;
        }
        byte[] expected = sign(secret, webhookId + "." + timestamp.trim() + "." + rawBody);
        for (String part : signatureHeader.trim().split(" ")) {
            int comma = part.indexOf(',');
            if (comma < 0 || !"v1".equals(part.substring(0, comma))) continue;
            try {
                byte[] given = Base64.getDecoder().decode(part.substring(comma + 1));
                if (MessageDigest.isEqual(expected, given)) return true;
            } catch (IllegalArgumentException ignored) {
                // 형식이 깨진 서명은 건너뛴다
            }
        }
        return false;
    }

    static byte[] sign(String secret, String payload) {
        try {
            String encoded = secret.startsWith("whsec_") ? secret.substring("whsec_".length()) : secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(encoded), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("웹훅 서명 키가 올바르지 않습니다", e);
        }
    }
}
