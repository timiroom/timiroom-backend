package com.timiroom.domain.usage.entity;

import com.timiroom.domain.usage.enums.PlanType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 회원 요금제 상태. 행이 없으면 무료 요금제다.
 * 프로는 결제한 기간(period_start ~ period_end) 동안 유지되고, 사용량도 이 기간 단위로 센다.
 */
@Entity
@Table(name = "member_subscription")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberSubscription {

    public enum Status {
        /** 무료 사용 중이거나 프로 결제가 정상 */
        ACTIVE,
        /** 정기결제 실패, 다시 시도하는 중 */
        PAST_DUE
    }

    @Id
    @Column(name = "member_id")
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlanType plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "period_start")
    private Instant periodStart;

    @Column(name = "period_end")
    private Instant periodEnd;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd;

    @Column(name = "next_billing_at")
    private Instant nextBillingAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "granted_by_admin", nullable = false)
    private boolean grantedByAdmin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static MemberSubscription free(Long memberId) {
        MemberSubscription s = new MemberSubscription();
        s.memberId = memberId;
        s.plan = PlanType.FREE;
        s.status = Status.ACTIVE;
        s.createdAt = Instant.now();
        s.updatedAt = s.createdAt;
        return s;
    }

    public boolean isPro() {
        return plan == PlanType.PRO;
    }

    /** 결제 성공: 프로 기간을 시작하거나 한 달 연장한다. */
    public void startProPeriod(Instant start, Instant end) {
        this.plan = PlanType.PRO;
        this.status = Status.ACTIVE;
        this.periodStart = start;
        this.periodEnd = end;
        this.nextBillingAt = end;
        this.failedAttempts = 0;
        this.grantedByAdmin = false;
        touch();
    }

    /** 정기결제 실패. 다음 날 다시 시도한다. */
    public void markPaymentFailed(Instant retryAt) {
        this.status = Status.PAST_DUE;
        this.failedAttempts++;
        this.nextBillingAt = retryAt;
        touch();
    }

    public void scheduleCancel(boolean cancel) {
        this.cancelAtPeriodEnd = cancel;
        touch();
    }

    /** 무료로 돌린다. 등록한 카드는 결제 수단에 그대로 남는다. */
    public void downgradeToFree() {
        this.plan = PlanType.FREE;
        this.status = Status.ACTIVE;
        this.periodStart = null;
        this.periodEnd = null;
        this.nextBillingAt = null;
        this.cancelAtPeriodEnd = false;
        this.failedAttempts = 0;
        this.grantedByAdmin = false;
        touch();
    }

    /** 관리자가 결제 없이 프로 기간을 준다. 정기결제는 걸리지 않는다. */
    public void grantPro(Instant start, Instant end) {
        this.plan = PlanType.PRO;
        this.status = Status.ACTIVE;
        this.periodStart = start;
        this.periodEnd = end;
        this.nextBillingAt = null;
        this.cancelAtPeriodEnd = true;
        this.grantedByAdmin = true;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
