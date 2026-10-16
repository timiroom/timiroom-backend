package com.timiroom.domain.billing.repository;

import com.timiroom.domain.billing.entity.BillingPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BillingPaymentRepository extends JpaRepository<BillingPayment, Long> {
    Page<BillingPayment> findByMemberIdOrderByCreatedAtDesc(Long memberId, Pageable pageable);
    Optional<BillingPayment> findByIdAndMemberId(Long id, Long memberId);
    Optional<BillingPayment> findByPaymentId(String paymentId);
}
