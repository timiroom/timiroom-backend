package com.timiroom.domain.billing.entity;

import com.timiroom.domain.usage.enums.PlanType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 결제 한 건. PortOne의 paymentId로 다시 조회해 상태·영수증을 맞춘다. */
@Entity
@Table(name = "billing_payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillingPayment {

    public enum Status { PENDING, PAID, FAILED, CANCELLED }

    /** 요금제 결제인지 추가 사용량 구매인지 */
    public enum Kind { SUBSCRIPTION, CREDIT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "billing_payment_id")
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "payment_id", nullable = false, length = 64, unique = true)
    private String paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PlanType plan;

    @Column(name = "credit_krw", precision = 14, scale = 4)
    private java.math.BigDecimal creditKrw;

    @Column(name = "amount_krw", nullable = false)
    private long amountKrw;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "order_name", nullable = false, length = 120)
    private String orderName;

    @Column(name = "period_start")
    private Instant periodStart;

    @Column(name = "period_end")
    private Instant periodEnd;

    @Column(name = "card_summary", length = 80)
    private String cardSummary;

    @Column(name = "receipt_url", columnDefinition = "TEXT")
    private String receiptUrl;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static BillingPayment pending(Long memberId, String paymentId, PlanType plan, long amountKrw,
                                         String orderName, Instant periodStart, Instant periodEnd, String cardSummary) {
        BillingPayment p = base(memberId, paymentId, amountKrw, orderName, cardSummary);
        p.kind = Kind.SUBSCRIPTION;
        p.plan = plan;
        p.periodStart = periodStart;
        p.periodEnd = periodEnd;
        return p;
    }

    public static BillingPayment pendingCredit(Long memberId, String paymentId, java.math.BigDecimal creditKrw, long amountKrw,
                                               String orderName, String cardSummary) {
        BillingPayment p = base(memberId, paymentId, amountKrw, orderName, cardSummary);
        p.kind = Kind.CREDIT;
        p.creditKrw = creditKrw;
        return p;
    }

    private static BillingPayment base(Long memberId, String paymentId, long amountKrw, String orderName, String cardSummary) {
        BillingPayment p = new BillingPayment();
        p.memberId = memberId;
        p.paymentId = paymentId;
        p.amountKrw = amountKrw;
        p.status = Status.PENDING;
        p.orderName = orderName;
        p.cardSummary = cardSummary;
        p.createdAt = Instant.now();
        return p;
    }

    public void markPaid(Instant paidAt, String receiptUrl, String cardSummary) {
        this.status = Status.PAID;
        this.paidAt = paidAt != null ? paidAt : Instant.now();
        if (receiptUrl != null && !receiptUrl.isBlank()) this.receiptUrl = receiptUrl;
        if (cardSummary != null && !cardSummary.isBlank()) this.cardSummary = cardSummary;
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        this.status = Status.FAILED;
        this.failureReason = reason == null ? null : (reason.length() > 300 ? reason.substring(0, 300) : reason);
    }

    public void markCancelled() {
        this.status = Status.CANCELLED;
    }
}
