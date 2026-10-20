package com.timiroom.domain.billing.repository;

import com.timiroom.domain.billing.entity.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {
    List<PaymentMethod> findByMemberIdOrderByCreatedAtAsc(Long memberId);
    Optional<PaymentMethod> findByIdAndMemberId(Long id, Long memberId);
    Optional<PaymentMethod> findFirstByMemberIdAndDefaultMethodTrue(Long memberId);
    long countByMemberId(Long memberId);
}
