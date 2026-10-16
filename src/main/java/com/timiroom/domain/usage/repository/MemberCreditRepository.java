package com.timiroom.domain.usage.repository;

import com.timiroom.domain.usage.entity.MemberCredit;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MemberCreditRepository extends JpaRepository<MemberCredit, Long> {

    /** 차감·충전은 동시에 일어날 수 있어 행을 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MemberCredit c where c.memberId = :memberId")
    Optional<MemberCredit> findForUpdate(@Param("memberId") Long memberId);
}
