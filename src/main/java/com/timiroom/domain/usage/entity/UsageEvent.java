package com.timiroom.domain.usage.entity;

import com.timiroom.domain.usage.enums.UsageFeature;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/** AI 호출 한 묶음(같은 기능·모델)의 사용 기록. 비용은 기록 시점의 단가로 계산해 고정한다. */
@Entity
@Table(name = "ai_usage_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "usage_event_id")
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "project_id")
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private UsageFeature feature;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, length = 120)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(nullable = false)
    private int calls;

    @Column(name = "cost_krw", nullable = false, precision = 14, scale = 4)
    private BigDecimal costKrw;

    /** 비용 중 추가 사용량에서 차감한 몫 */
    @Column(name = "credit_krw", nullable = false, precision = 14, scale = 4)
    private BigDecimal creditKrw = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean byok;

    @Column(nullable = false)
    private boolean counted;

    @Column(length = 80)
    private String reference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Builder
    public UsageEvent(Long memberId, Long projectId, UsageFeature feature, String kind, String provider, String model,
                      long inputTokens, long outputTokens, int calls, BigDecimal costKrw, BigDecimal creditKrw, boolean byok,
                      boolean counted, String reference, Instant createdAt) {
        this.memberId = memberId;
        this.projectId = projectId;
        this.feature = feature;
        this.kind = kind;
        this.provider = provider;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.calls = calls;
        this.costKrw = costKrw;
        this.creditKrw = creditKrw != null ? creditKrw : BigDecimal.ZERO;
        this.byok = byok;
        this.counted = counted;
        this.reference = reference;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }
}
