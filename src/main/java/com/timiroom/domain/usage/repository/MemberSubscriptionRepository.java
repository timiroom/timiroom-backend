package com.timiroom.domain.usage.repository;

import com.timiroom.domain.usage.entity.MemberSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface MemberSubscriptionRepository extends JpaRepository<MemberSubscription, Long> {

    /** 결제일이 지난 정기결제 대상(관리자 부여 제외). */
    List<MemberSubscription> findByNextBillingAtLessThanEqualAndGrantedByAdminFalse(Instant now);

    /** 곧 자동 결제될 구독(결제 예정 알림) */
    List<MemberSubscription> findByNextBillingAtBetweenAndGrantedByAdminFalse(Instant from, Instant to);

    /** 기간이 끝난 프로(해지 예약·관리자 부여 포함). */
    List<MemberSubscription> findByPeriodEndLessThanEqualAndCancelAtPeriodEndTrue(Instant now);
}
