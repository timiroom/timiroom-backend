package com.timiroom.domain.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.timiroom.domain.billing.entity.BillingPayment;
import com.timiroom.domain.billing.repository.BillingPaymentRepository;
import com.timiroom.domain.usage.entity.MemberSubscription;
import com.timiroom.domain.usage.repository.MemberSubscriptionRepository;
import com.timiroom.domain.usage.repository.UsageEventRepository;
import com.timiroom.domain.usage.service.UsageService;
import com.timiroom.infra.portone.PortOneClient;
import com.timiroom.infra.portone.PortOneClient.PortOneException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 환불. 규칙은 약관(환불 정책)과 같다.
 *   프로 요금제: 결제 후 7일 안에, 그 기간 AI를 쓰지 않았으면 전액 환불하고 바로 무료로 돌린다.
 *               그 밖에는 환불 없이 해지만 된다(남은 기간은 쓴다).
 *   추가 사용량: 쓰지 않은 잔액은 결제일로부터 1년 안의 충전분에서 최근 것부터 카드 결제를 취소해 돌려준다.
 *   관리자: 장애 보상 등으로 결제 한 건을 원하는 금액만큼 환불한다.
 * 같은 회원의 환불은 DB 잠금으로 한 번에 하나씩만 처리한다(서버가 여러 대여도).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    public static final int SUBSCRIPTION_REFUND_DAYS = 7;
    public static final int CREDIT_REFUND_DAYS = 365;
    /** 이보다 적게 쓴 경우는 쓰지 않은 것으로 본다(원 단위 반올림 오차) */
    private static final BigDecimal UNUSED_THRESHOLD_KRW = BigDecimal.ONE;

    private final BillingPaymentRepository payments;
    private final MemberSubscriptionRepository subscriptions;
    private final UsageEventRepository usageEvents;
    private final UsageService usageService;
    private final PortOneClient portOne;
    private final JdbcTemplate jdbc;
    private final BillingNotifier notifier;

    /** 프로 요금제 환불 가능 여부 */
    public record SubscriptionRefund(boolean eligible, long amountKrw, String reason, BillingPayment payment) {}

    // ── 사용자 ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> options(Long memberId) {
        SubscriptionRefund sub = subscriptionRefund(memberId, Instant.now());
        Map<String, Object> subscription = new LinkedHashMap<>();
        subscription.put("eligible", sub.eligible());
        subscription.put("amountKrw", sub.amountKrw());
        subscription.put("reason", sub.reason());
        Map<String, Object> credit = new LinkedHashMap<>();
        credit.put("refundableKrw", creditRefundable(memberId, Instant.now()));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("subscription", subscription);
        view.put("credit", credit);
        view.put("subscriptionRefundDays", SUBSCRIPTION_REFUND_DAYS);
        view.put("creditRefundDays", CREDIT_REFUND_DAYS);
        return view;
    }

    /** 프로 요금제 청약철회: 전액 환불 후 바로 무료로 돌린다. */
    @Transactional
    public Map<String, Object> refundSubscription(Long memberId) {
        lockMember(memberId);
        SubscriptionRefund sub = subscriptionRefund(memberId, Instant.now());
        if (!sub.eligible()) throw new IllegalArgumentException(sub.reason());
        BillingPayment p = sub.payment();
        cancel(p, p.refundableKrw(), "프로 요금제 청약철회(결제 후 " + SUBSCRIPTION_REFUND_DAYS + "일 이내, 미사용)");
        subscriptions.findById(memberId).ifPresent(MemberSubscription::downgradeToFree);
        notifier.refunded(memberId, sub.amountKrw(), "프로 요금제");
        return Map.of("refundedKrw", sub.amountKrw());
    }

    /** 추가 사용량의 쓰지 않은 잔액을 환불한다. 일부 결제 취소가 실패하면 거기까지만 환불한다. */
    @Transactional
    public Map<String, Object> refundCredit(Long memberId) {
        lockMember(memberId);
        Instant now = Instant.now();
        long target = creditRefundable(memberId, now);
        if (target <= 0) throw new IllegalArgumentException("환불할 수 있는 추가 사용량 잔액이 없습니다");
        long remaining = target;
        String failure = null;
        for (BillingPayment p : refundableCreditPayments(memberId, now)) {
            if (remaining <= 0) break;
            long amount = Math.min(remaining, p.refundableKrw());
            try {
                cancel(p, amount, "추가 사용량 미사용 잔액 환불");
            } catch (IllegalArgumentException e) {
                failure = e.getMessage();
                break;
            }
            usageService.takeCredit(memberId, BigDecimal.valueOf(amount));
            remaining -= amount;
        }
        long refunded = target - remaining;
        if (refunded == 0) throw new IllegalArgumentException(failure != null ? failure : "환불하지 못했습니다");
        notifier.refunded(memberId, refunded, "추가 사용량 잔액");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("refundedKrw", refunded);
        if (failure != null) result.put("warning", "일부만 환불했습니다: " + failure);
        return result;
    }

    // ── 관리자 ─────────────────────────────────────────────

    /** 결제 한 건을 amountKrw(없으면 남은 전액)만큼 환불한다. 추가 사용량이면 잔액에서도 뺀다. */
    @Transactional
    public Map<String, Object> adminRefund(Long billingPaymentId, Long amountKrw, String reason, boolean downgrade) {
        BillingPayment p = payments.findById(billingPaymentId)
                .orElseThrow(() -> new IllegalArgumentException("결제를 찾을 수 없습니다"));
        lockMember(p.getMemberId());
        long amount = amountKrw == null ? p.refundableKrw() : amountKrw;
        if (amount <= 0 || amount > p.refundableKrw()) {
            throw new IllegalArgumentException(String.format("환불 금액은 1원부터 %,d원까지입니다", p.refundableKrw()));
        }
        String why = reason == null || reason.isBlank() ? "관리자 환불" : reason.trim();
        cancel(p, amount, why);
        if (p.getKind() == BillingPayment.Kind.CREDIT) usageService.takeCredit(p.getMemberId(), BigDecimal.valueOf(amount));
        if (downgrade && p.getKind() == BillingPayment.Kind.SUBSCRIPTION) {
            subscriptions.findById(p.getMemberId()).ifPresent(MemberSubscription::downgradeToFree);
        }
        notifier.refunded(p.getMemberId(), amount, p.getKind() == BillingPayment.Kind.CREDIT ? "추가 사용량" : "프로 요금제");
        return BillingService.paymentView(p);
    }

    // ── 웹훅 ───────────────────────────────────────────────

    /**
     * PortOne 콘솔 등 다른 곳에서 취소된 금액을 반영한다. 우리가 이미 기록한 만큼은 건너뛴다.
     * 추가 사용량이면 잔액에서 빼고, 요금제 결제가 전액 취소되면 그 기간이면 무료로 돌린다.
     */
    @Transactional
    public void applyRemoteCancellation(BillingPayment p, long remoteCancelledKrw) {
        lockMember(p.getMemberId());
        long delta = remoteCancelledKrw - p.getRefundedKrw();
        if (delta <= 0) return;
        p.recordRefund(remoteCancelledKrw, "결제 서비스에서 취소");
        if (p.getKind() == BillingPayment.Kind.CREDIT) {
            usageService.takeCredit(p.getMemberId(), BigDecimal.valueOf(delta));
        } else if (p.getStatus() == BillingPayment.Status.CANCELLED) {
            subscriptions.findById(p.getMemberId())
                    .filter(s -> s.isPro() && Objects.equals(s.getPeriodStart(), p.getPeriodStart()))
                    .ifPresent(MemberSubscription::downgradeToFree);
        }
        log.info("외부 취소 반영 | paymentId: {}, 추가 환불: {}원", p.getPaymentId(), delta);
    }

    // ── 규칙 ───────────────────────────────────────────────

    SubscriptionRefund subscriptionRefund(Long memberId, Instant now) {
        MemberSubscription s = subscriptions.findById(memberId).orElse(null);
        if (s == null || !s.isPro() || s.isGrantedByAdmin() || s.getPeriodStart() == null) {
            return new SubscriptionRefund(false, 0, "환불할 프로 요금제 결제가 없습니다", null);
        }
        BillingPayment p = payments.findFirstByMemberIdAndKindAndPeriodStartAndStatusOrderByCreatedAtDesc(
                memberId, BillingPayment.Kind.SUBSCRIPTION, s.getPeriodStart(), BillingPayment.Status.PAID).orElse(null);
        if (p == null || p.getPaidAt() == null || p.refundableKrw() <= 0) {
            return new SubscriptionRefund(false, 0, "환불할 프로 요금제 결제가 없습니다", null);
        }
        if (now.isAfter(p.getPaidAt().plus(Duration.ofDays(SUBSCRIPTION_REFUND_DAYS)))) {
            return new SubscriptionRefund(false, 0, "결제 후 " + SUBSCRIPTION_REFUND_DAYS
                    + "일이 지나 환불할 수 없습니다. 해지하면 다음 결제부터 청구되지 않습니다", p);
        }
        BigDecimal used = Objects.requireNonNullElse(usageEvents.sumCountedCost(memberId, s.getPeriodStart(), now), BigDecimal.ZERO);
        if (used.compareTo(UNUSED_THRESHOLD_KRW) >= 0) {
            return new SubscriptionRefund(false, 0, "이번 기간에 AI 기능을 사용해 환불할 수 없습니다. 해지하면 다음 결제부터 청구되지 않습니다", p);
        }
        return new SubscriptionRefund(true, p.refundableKrw(), null, p);
    }

    /** 환불할 수 있는 추가 사용량: 잔액과 1년 안의 충전 결제 중 남은 금액 가운데 작은 쪽 */
    long creditRefundable(Long memberId, Instant now) {
        long balance = usageService.creditBalance(memberId).setScale(0, RoundingMode.DOWN).longValue();
        long paid = refundableCreditPayments(memberId, now).stream().mapToLong(BillingPayment::refundableKrw).sum();
        return Math.max(0, Math.min(balance, paid));
    }

    private List<BillingPayment> refundableCreditPayments(Long memberId, Instant now) {
        return payments.findByMemberIdAndKindAndPaidAtAfterOrderByPaidAtDesc(memberId, BillingPayment.Kind.CREDIT,
                        now.minus(Duration.ofDays(CREDIT_REFUND_DAYS))).stream()
                .filter(p -> p.refundableKrw() > 0)
                .toList();
    }

    /** PortOne에 취소를 요청하고 기록한다. 실패하면 사용자에게 보여 줄 문장으로 IllegalArgumentException. */
    private void cancel(BillingPayment p, long amount, String reason) {
        JsonNode result;
        try {
            result = portOne.cancelPayment(p.getPaymentId(), amount, p.refundableKrw(), reason);
        } catch (PortOneException e) {
            throw new IllegalArgumentException("환불하지 못했습니다: " + e.getMessage());
        }
        String status = result.path("status").asText("");
        if ("FAILED".equals(status)) {
            throw new IllegalArgumentException("환불하지 못했습니다: " + result.path("reason").asText("카드사 거절"));
        }
        // REQUESTED(카드사 처리 중)도 접수된 것으로 보고 기록한다. 최종 결과는 웹훅으로 다시 맞춘다.
        p.recordRefund(p.getRefundedKrw() + amount, reason);
        log.info("환불 | paymentId: {}, 금액: {}원, 상태: {}", p.getPaymentId(), amount, status);
    }

    private void lockMember(Long memberId) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 7))", Object.class, "refund:" + memberId);
    }
}
