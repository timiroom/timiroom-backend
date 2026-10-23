package com.timiroom.domain.mock.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Mock 서버 주소에 넣는 키. "{projectId}-{서명}" 형태라 프로젝트 번호만으로는 다른 프로젝트 명세를 볼 수 없다.
 * 로그인 없이 curl·Postman으로 부를 수 있게 키는 주소에 담고, 키는 프로젝트 멤버에게만 내려 준다.
 */
@Slf4j
@Component
public class MockUrlSigner {

    private static final String LABEL = "timiroom-mock:";
    private static final int SIGNATURE_CHARS = 22;

    private final byte[] secret;

    public MockUrlSigner(@Value("${mock.signing-secret:${integration.cursor-secret:}}") String configured) {
        if (configured == null || configured.isBlank()) {
            // 비밀값이 없으면 실행마다 새 키를 쓴다. 재시작하면 예전 Mock 주소는 더 이상 열리지 않는다.
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
            log.warn("mock.signing-secret이 없어 임시 키로 Mock 주소를 서명합니다. 재시작하면 Mock 주소가 바뀝니다.");
        } else {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String keyFor(Long projectId) {
        return projectId + "-" + signature(projectId);
    }

    /** 키가 올바르면 프로젝트 번호를, 아니면 빈 값을 돌려준다. */
    public Optional<Long> projectIdOf(String key) {
        if (key == null) return Optional.empty();
        int dash = key.indexOf('-');
        if (dash <= 0 || dash == key.length() - 1) return Optional.empty();
        long projectId;
        try {
            projectId = Long.parseLong(key.substring(0, dash));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        byte[] expected = signature(projectId).getBytes(StandardCharsets.US_ASCII);
        byte[] given = key.substring(dash + 1).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given) ? Optional.of(projectId) : Optional.empty();
    }

    private String signature(long projectId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal((LABEL + projectId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, SIGNATURE_CHARS);
        } catch (Exception e) {
            throw new IllegalStateException("Mock 주소 서명에 실패했습니다", e);
        }
    }
}
