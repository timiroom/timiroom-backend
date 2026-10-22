package com.timiroom.domain.billing.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 등록한 카드(PortOne 빌링키). 기본 카드로 정기결제와 추가 사용량 구매를 한다. */
@Entity
@Table(name = "payment_method")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_method_id")
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "billing_key_encrypted", nullable = false, columnDefinition = "TEXT")
    private String billingKeyEncrypted;

    @Column(name = "card_summary", length = 80)
    private String cardSummary;

    @Column(name = "is_default", nullable = false)
    private boolean defaultMethod;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static PaymentMethod create(Long memberId, String billingKeyEncrypted, String cardSummary, boolean defaultMethod) {
        PaymentMethod m = new PaymentMethod();
        m.memberId = memberId;
        m.billingKeyEncrypted = billingKeyEncrypted;
        m.cardSummary = cardSummary;
        m.defaultMethod = defaultMethod;
        m.createdAt = Instant.now();
        return m;
    }

    public void setDefaultMethod(boolean defaultMethod) {
        this.defaultMethod = defaultMethod;
    }
}
