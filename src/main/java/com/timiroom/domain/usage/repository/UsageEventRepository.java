package com.timiroom.domain.usage.repository;

import com.timiroom.domain.usage.entity.UsageEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {

    @Query("select coalesce(sum(e.costKrw), 0) from UsageEvent e "
            + "where e.memberId = :memberId and e.counted = true and e.createdAt >= :from and e.createdAt < :to")
    BigDecimal sumCountedCost(@Param("memberId") Long memberId, @Param("from") Instant from, @Param("to") Instant to);

    List<UsageEvent> findByMemberIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            Long memberId, Instant from, Instant to);

    Page<UsageEvent> findByMemberIdOrderByCreatedAtDesc(Long memberId, Pageable pageable);
}
