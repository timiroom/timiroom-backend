package com.timiroom.domain.usage.repository;

import com.timiroom.domain.usage.entity.UsageEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;

public interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {

    /** 요금제 한도에서 쓴 양(추가 사용량에서 차감한 몫은 뺀다) */
    @Query("select coalesce(sum(e.costKrw - e.creditKrw), 0) from UsageEvent e "
            + "where e.memberId = :memberId and e.counted = true and e.createdAt >= :from and e.createdAt < :to")
    BigDecimal sumCountedCost(@Param("memberId") Long memberId, @Param("from") Instant from, @Param("to") Instant to);

    /** 이번 기간 추가 사용량으로 덮은 비용 합계(월 상한 계산용) */
    @Query("select coalesce(sum(e.creditKrw), 0) from UsageEvent e "
            + "where e.memberId = :memberId and e.createdAt >= :from and e.createdAt < :to")
    BigDecimal sumCreditCovered(@Param("memberId") Long memberId, @Param("from") Instant from, @Param("to") Instant to);

    /** 기능별 비중 계산용: [feature, 합계] */
    @Query("select e.feature, coalesce(sum(e.costKrw), 0) from UsageEvent e "
            + "where e.memberId = :memberId and e.counted = true and e.createdAt >= :from and e.createdAt < :to group by e.feature")
    java.util.List<Object[]> sumCostByFeature(@Param("memberId") Long memberId, @Param("from") Instant from, @Param("to") Instant to);
}
