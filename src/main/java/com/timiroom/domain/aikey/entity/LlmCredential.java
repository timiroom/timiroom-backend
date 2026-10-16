package com.timiroom.domain.aikey.entity;

import com.timiroom.domain.aikey.LlmProvider;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 사용자가 등록한 AI 키. 키는 암호화해 저장하고, 화면에는 끝자리(keyHint)만 보여 준다. */
@Entity
@Table(name = "llm_credential")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LlmCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "llm_credential_id")
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LlmProvider provider;

    @Column(name = "base_url", nullable = false, length = 300)
    private String baseUrl;

    @Column(nullable = false, length = 120)
    private String model;

    @Column(name = "api_key_encrypted", nullable = false, columnDefinition = "TEXT")
    private String apiKeyEncrypted;

    @Column(name = "key_hint", nullable = false, length = 20)
    private String keyHint;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "last_error", length = 300)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static LlmCredential create(Long memberId, LlmProvider provider, String baseUrl, String model,
                                       String apiKeyEncrypted, String keyHint) {
        LlmCredential c = new LlmCredential();
        c.memberId = memberId;
        c.provider = provider;
        c.baseUrl = baseUrl;
        c.model = model;
        c.apiKeyEncrypted = apiKeyEncrypted;
        c.keyHint = keyHint;
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        return c;
    }

    public void verified() {
        this.lastVerifiedAt = Instant.now();
        this.lastError = null;
        this.updatedAt = this.lastVerifiedAt;
    }

    public void failed(String error) {
        this.lastError = error == null ? null : (error.length() > 300 ? error.substring(0, 300) : error);
        this.updatedAt = Instant.now();
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public void changeModel(String model) {
        this.model = model;
        this.updatedAt = Instant.now();
    }
}
