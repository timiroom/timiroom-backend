package com.timiroom.domain.billing.repository;

import com.timiroom.domain.billing.entity.BillingPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BillingPaymentRepository extends JpaRepository<BillingPayment, Long> {
    Page<BillingPayment> findByMemberIdOrderByCreatedAtDesc(Long memberId, Pageable pageable);
    Optional<BillingPayment> findByIdAndMemberId(Long id, Long memberId);
    Optional<BillingPayment> findByPaymentId(String paymentId);

    /** 지금 프로 기간을 연 결제 */
    Optional<BillingPayment> findFirstByMemberIdAndKindAndPeriodStartAndStatusOrderByCreatedAtDesc(
            Long memberId, BillingPayment.Kind kind, Instant periodStart, BillingPayment.Status status);

    /** 환불 후보가 되는 기간 안의 결제(최근 것부터) */
    List<BillingPayment> findByMemberIdAndKindAndPaidAtAfterOrderByPaidAtDesc(Long memberId, BillingPayment.Kind kind, Instant after);

    /** 관리자 결제 목록 */
    Page<BillingPayment> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<BillingPayment> findByStatusOrderByCreatedAtDesc(BillingPayment.Status status, Pageable pageable);
}
