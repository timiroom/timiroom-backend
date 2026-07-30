package com.timiroom.infra.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GitHub App 인증 — private key로 App JWT를 서명하고,
 * installation access token을 발급/캐싱한다 (만료 5분 전 갱신).
 *
 * 키가 설정되지 않으면 비활성 상태로 부팅되고, 사용 시점에 예외를 던진다.
 */
@Slf4j
@Service
public class GithubAppAuthService {

    private static final long JWT_TTL_SECONDS = 540;          // GitHub 최대 10분, 여유를 둔 9분
    private static final long TOKEN_REFRESH_MARGIN_SECONDS = 300; // 만료 5분 전 갱신

    private final String appId;
    private final PrivateKey privateKey; // null이면 GitHub 연동 비활성
    private final WebClient webClient;

    private final AtomicReference<CachedValue> appJwtCache = new AtomicReference<>();
    private final Map<Long, CachedValue> installationTokenCache = new ConcurrentHashMap<>();

    public GithubAppAuthService(
            @Value("${github.app.id:}") String appId,
            @Value("${github.app.private-key:}") String privateKeyContent,
            @Value("${github.app.private-key-path:}") String privateKeyPath,
            @Value("${github.api-base-url:https://api.github.com}") String apiBaseUrl) {

        this.appId = appId;
        this.privateKey = loadPrivateKey(privateKeyContent, privateKeyPath);
        this.webClient = WebClient.builder().baseUrl(apiBaseUrl).build();

        if (isEnabled()) {
            log.info("GitHub App 인증 준비 완료 — appId: {}", appId);
        } else {
            log.warn("GitHub App 미설정 (GITHUB_APP_ID / GITHUB_APP_PRIVATE_KEY[_PATH]). GitHub 연동 기능 비활성.");
        }
    }

    public boolean isEnabled() {
        return privateKey != null && appId != null && !appId.isBlank();
    }

    /** App JWT (iss = appId, RS256). 만료 1분 전까지 재사용 */
    public String createAppJwt() {
        ensureEnabled();

        CachedValue cached = appJwtCache.get();
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cached.expiresAt().minusSeconds(60))) {
            return cached.value();
        }

        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(appId)
                    .issueTime(Date.from(now.minusSeconds(60))) // 시계 오차 보정
                    .expirationTime(Date.from(now.plusSeconds(JWT_TTL_SECONDS)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(privateKey));

            String token = jwt.serialize();
            appJwtCache.set(new CachedValue(token, now.plusSeconds(JWT_TTL_SECONDS)));
            return token;
        } catch (JOSEException e) {
            throw new IllegalStateException("GitHub App JWT 서명 실패", e);
        }
    }

    /** installation access token — 1시간 만료, 5분 전 자동 갱신 */
    public String getInstallationToken(long installationId) {
        ensureEnabled();

        CachedValue cached = installationTokenCache.get(installationId);
        if (cached != null && Instant.now().isBefore(cached.expiresAt().minusSeconds(TOKEN_REFRESH_MARGIN_SECONDS))) {
            return cached.value();
        }

        synchronized (installationTokenCache) {
            cached = installationTokenCache.get(installationId);
            if (cached != null && Instant.now().isBefore(cached.expiresAt().minusSeconds(TOKEN_REFRESH_MARGIN_SECONDS))) {
                return cached.value();
            }

            JsonNode body = webClient.post()
                    .uri("/app/installations/{id}/access_tokens", installationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + createAppJwt())
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class).defaultIfEmpty("")
                            .map(b -> new IllegalStateException(
                                    "installation token 발급 실패 (" + r.statusCode() + "): " + b)))
                    .bodyToMono(JsonNode.class)
                    .block();

            String token = body.get("token").asText();
            Instant expiresAt = Instant.parse(body.get("expires_at").asText());
            installationTokenCache.put(installationId, new CachedValue(token, expiresAt));
            log.info("GitHub installation token 발급 — installationId: {}, 만료: {}", installationId, expiresAt);
            return token;
        }
    }

    private void ensureEnabled() {
        if (!isEnabled()) {
            throw new IllegalStateException(
                    "GitHub App이 설정되지 않았습니다. GITHUB_APP_ID와 GITHUB_APP_PRIVATE_KEY(_PATH)를 확인해주세요.");
        }
    }

    private record CachedValue(String value, Instant expiresAt) {}

    // ── private key 로딩 ─────────────────────────────────────────

    private static PrivateKey loadPrivateKey(String content, String path) {
        try {
            String pem = null;
            if (content != null && !content.isBlank()) {
                pem = content.replace("\\n", "\n"); // k8s secret 등에서 이스케이프된 개행 복원
            } else if (path != null && !path.isBlank()) {
                pem = Files.readString(Path.of(path));
            }
            if (pem == null || pem.isBlank()) return null;
            return parsePem(pem);
        } catch (Exception e) {
            log.error("GitHub App private key 로딩 실패: {}", e.getMessage());
            return null;
        }
    }

    /** PKCS#8(BEGIN PRIVATE KEY)과 GitHub 기본 포맷 PKCS#1(BEGIN RSA PRIVATE KEY) 모두 지원 */
    private static PrivateKey parsePem(String pem) throws Exception {
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String base64 = pem
                .replaceAll("-----BEGIN (RSA )?PRIVATE KEY-----", "")
                .replaceAll("-----END (RSA )?PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        if (pkcs1) {
            der = wrapPkcs1InPkcs8(der);
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /**
     * PKCS#1 RSAPrivateKey DER를 PKCS#8 PrivateKeyInfo로 감싼다.
     * PrivateKeyInfo ::= SEQUENCE { version(0), AlgorithmIdentifier(rsaEncryption), OCTET STRING(pkcs1) }
     */
    private static byte[] wrapPkcs1InPkcs8(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] rsaAlgorithmId = {
                0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] keyOctetString = derEncode((byte) 0x04, pkcs1);
        byte[] body = concat(version, rsaAlgorithmId, keyOctetString);
        return derEncode((byte) 0x30, body);
    }

    private static byte[] derEncode(byte tag, byte[] content) {
        int n = content.length;
        byte[] length;
        if (n < 0x80) {
            length = new byte[]{(byte) n};
        } else if (n <= 0xFF) {
            length = new byte[]{(byte) 0x81, (byte) n};
        } else if (n <= 0xFFFF) {
            length = new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
        } else {
            length = new byte[]{(byte) 0x83, (byte) (n >> 16), (byte) (n >> 8), (byte) n};
        }
        byte[] out = new byte[1 + length.length + n];
        out[0] = tag;
        System.arraycopy(length, 0, out, 1, length.length);
        System.arraycopy(content, 0, out, 1 + length.length, n);
        return out;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }
}
