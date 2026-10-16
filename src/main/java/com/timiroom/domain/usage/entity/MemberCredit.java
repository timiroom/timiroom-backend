package com.timiroom.domain.usage.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/** 추가 사용량 잔액(내부 원 단위). 요금제 한도를 다 쓴 뒤부터 여기서 차감한다. 사용자에게는 비율로만 보인다. */
@Entity
@Table(name = "member_credit")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberCredit {

    @Id
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "balance_krw", nullable = false, precision = 14, scale = 4)
    private BigDecimal balanceKrw;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "monthly_cap_krw")
    private Long monthlyCapKrw;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void configure(boolean enabled, Long monthlyCapKrw) {
        this.enabled = enabled;
        this.monthlyCapKrw = monthlyCapKrw;
        this.updatedAt = Instant.now();
    }

    public static MemberCredit empty(Long memberId) {
        MemberCredit c = new MemberCredit();
        c.memberId = memberId;
        c.balanceKrw = BigDecimal.ZERO;
        c.updatedAt = Instant.now();
        return c;
    }

    public void add(BigDecimal amount) {
        this.balanceKrw = this.balanceKrw.add(amount);
        this.updatedAt = Instant.now();
    }

    /** 최대 amount만큼 차감하고 실제로 차감한 금액을 돌려준다. */
    public BigDecimal take(BigDecimal amount) {
        BigDecimal taken = amount.min(balanceKrw).max(BigDecimal.ZERO);
        this.balanceKrw = this.balanceKrw.subtract(taken);
        this.updatedAt = Instant.now();
        return taken;
    }
}
