package com.timiroom.domain.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.timiroom.domain.aikey.service.SecretCipher;
import com.timiroom.domain.billing.entity.BillingPayment;
import com.timiroom.domain.billing.repository.BillingPaymentRepository;
import com.timiroom.domain.member.repository.MemberRepository;
import com.timiroom.domain.usage.UsageProperties;
import com.timiroom.domain.usage.entity.MemberSubscription;
import com.timiroom.domain.usage.enums.PlanType;
import com.timiroom.domain.usage.repository.MemberSubscriptionRepository;
import com.timiroom.infra.portone.PortOneClient;
import com.timiroom.infra.portone.PortOneClient.PortOneException;
import com.timiroom.infra.portone.PortOneProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 프로 요금제 정기결제(PortOne V2 빌링키).
 *   구독: 브라우저가 PortOne 창에서 카드를 등록해 빌링키를 받음 → 서버가 고객 확인 후 첫 달 결제 → 프로 시작
 *   갱신: 스케줄러가 매일 결제일이 지난 구독을 같은 빌링키로 결제한다. 실패하면 하루 뒤 다시, 3번 실패하면 무료로 돌린다.
 *   해지: 기간 끝에 무료로 돌린다(남은 기간은 그대로 쓴다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    static final int MAX_FAILED_ATTEMPTS = 3;
    private static final DateTimeFormatter PAYMENT_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final PortOneClient portOne;
    private final PortOneProperties portOneProperties;
    private final UsageProperties usageProperties;
    private final MemberSubscriptionRepository subscriptions;
    private final BillingPaymentRepository payments;
    private final MemberRepository members;
    private final SecretCipher cipher;

    /** 브라우저가 PortOne 결제창을 열 때 쓰는 공개 값 */
    public Map<String, Object> clientConfig() {
        boolean ready = portOneProperties.isReady() && cipher.isEnabled();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", ready);
        config.put("storeId", ready ? portOneProperties.getStoreId() : "");
        config.put("channelKey", ready ? portOneProperties.getChannelKey() : "");
        config.put("priceKrw", usageProperties.plan(PlanType.PRO).getPriceKrw());
        return config;
    }

    public static String customerId(Long memberId) {
        return "timiroom-member-" + memberId;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> subscriptionView(Long memberId) {
        return view(subscriptions.findById(memberId).orElseGet(() -> MemberSubscription.free(memberId)));
    }

    private Map<String, Object> view(MemberSubscription s) {
        Long memberId = s.getMemberId();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("plan", s.getPlan().name());
        view.put("status", s.getStatus().name());
        view.put("periodStart", s.getPeriodStart());
        view.put("periodEnd", s.getPeriodEnd());
        view.put("nextBillingAt", s.isCancelAtPeriodEnd() ? null : s.getNextBillingAt());
        view.put("cancelAtPeriodEnd", s.isCancelAtPeriodEnd());
        view.put("cardSummary", s.getCardSummary());
        view.put("hasCard", s.hasBillingKey());
        view.put("grantedByAdmin", s.isGrantedByAdmin());
        view.put("failedAttempts", s.getFailedAttempts());
        view.put("priceKrw", usageProperties.plan(PlanType.PRO).getPriceKrw());
        view.put("customerId", customerId(memberId));
        return view;
    }

    /** 첫 구독: 빌링키 확인 → 첫 달 결제 → 프로 시작. 결제 실패 기록은 남기려고 안내 예외에서 되돌리지 않는다. */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public Map<String, Object> subscribe(Long memberId, String billingKey) {
        requireReady();
        MemberSubscription s = subscriptions.findById(memberId).orElseGet(() -> MemberSubscription.free(memberId));
        Instant now = Instant.now();
        if (s.isPro() && s.getPeriodEnd() != null && now.isBefore(s.getPeriodEnd()) && !s.isGrantedByAdmin()) {
            throw new IllegalArgumentException("이미 프로 요금제를 쓰고 있습니다");
        }
        String card = verifyBillingKey(memberId, billingKey);
        Instant end = plusOneMonth(now);
        BillingPayment payment = charge(memberId, billingKey, now, end, card);
        if (payment.getStatus() != BillingPayment.Status.PAID) {
            portOne.deleteBillingKey(billingKey);
            throw new IllegalArgumentException(payment.getFailureReason() != null ? payment.getFailureReason() : "결제에 실패했습니다");
        }
        s.changeCard(cipher.encrypt(billingKey), payment.getCardSummary() != null ? payment.getCardSummary() : card);
        s.startProPeriod(now, end);
        s.scheduleCancel(false);
        return view(subscriptions.save(s));
    }

    /** 카드 변경: 새 빌링키로 바꾸고 이전 빌링키는 PortOne에서 지운다. 결제가 밀려 있으면 바로 다시 결제한다. */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public Map<String, Object> changeCard(Long memberId, String billingKey) {
        requireReady();
        MemberSubscription s = subscriptions.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("구독 중인 요금제가 없습니다"));
        String card = verifyBillingKey(memberId, billingKey);
        String previous = s.hasBillingKey() ? cipher.decrypt(s.getBillingKeyEncrypted()) : null;
        s.changeCard(cipher.encrypt(billingKey), card);
        if (previous != null && !previous.equals(billingKey)) portOne.deleteBillingKey(previous);
        if (s.getStatus() == MemberSubscription.Status.PAST_DUE) renew(s, Instant.now());
        return view(s);
    }

    @Transactional
    public Map<String, Object> cancel(Long memberId) {
        MemberSubscription s = subscriptions.findById(memberId)
                .filter(MemberSubscription::isPro)
                .orElseThrow(() -> new IllegalArgumentException("해지할 프로 요금제가 없습니다"));
        s.scheduleCancel(true);
        return view(s);
    }

    @Transactional
    public Map<String, Object> resume(Long memberId) {
        MemberSubscription s = subscriptions.findById(memberId)
                .filter(MemberSubscription::isPro)
                .orElseThrow(() -> new IllegalArgumentException("다시 이어 쓸 프로 요금제가 없습니다"));
        if (!s.hasBillingKey() || s.isGrantedByAdmin()) {
            throw new IllegalArgumentException("등록된 카드가 없어 이어 쓸 수 없습니다. 다시 구독해 주세요");
        }
        if (s.getPeriodEnd() != null && !Instant.now().isBefore(s.getPeriodEnd())) {
            throw new IllegalArgumentException("이미 기간이 끝났습니다. 다시 구독해 주세요");
        }
        s.scheduleCancel(false);
        return view(s);
    }

    @Transactional(readOnly = true)
    public Page<BillingPayment> history(Long memberId, int page, int size) {
        return payments.findByMemberIdOrderByCreatedAtDesc(memberId, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)));
    }

    /** 영수증 화면 데이터. PortOne 영수증 주소가 없으면 화면이 자체 영수증을 그린다. */
    @Transactional(readOnly = true)
    public Map<String, Object> receipt(Long memberId, Long id) {
        BillingPayment p = payments.findByIdAndMemberId(id, memberId)
                .orElseThrow(() -> new IllegalArgumentException("결제 내역을 찾을 수 없습니다"));
        var member = members.findById(memberId).orElse(null);
        Map<String, Object> view = paymentView(p);
        view.put("buyerName", member != null ? Optional.ofNullable(member.getMemberName()).orElse(member.getNickname()) : null);
        view.put("buyerEmail", member != null ? member.getEmail() : null);
        view.put("supplyKrw", Math.round(p.getAmountKrw() / 1.1));
        view.put("vatKrw", p.getAmountKrw() - Math.round(p.getAmountKrw() / 1.1));
        return view;
    }

    public static Map<String, Object> paymentView(BillingPayment p) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", p.getId());
        view.put("paymentId", p.getPaymentId());
        view.put("plan", p.getPlan().name());
        view.put("orderName", p.getOrderName());
        view.put("amountKrw", p.getAmountKrw());
        view.put("status", p.getStatus().name());
        view.put("periodStart", p.getPeriodStart());
        view.put("periodEnd", p.getPeriodEnd());
        view.put("cardSummary", p.getCardSummary());
        view.put("receiptUrl", p.getReceiptUrl());
        view.put("failureReason", p.getFailureReason());
        view.put("paidAt", p.getPaidAt());
        view.put("createdAt", p.getCreatedAt());
        return view;
    }

    /** 스케줄러: 기간이 끝난 해지 예약을 무료로 돌리고, 결제일이 된 구독을 갱신한다. */
    @Transactional
    public int runDueBilling(Instant now) {
        int handled = 0;
        for (MemberSubscription s : subscriptions.findByPeriodEndLessThanEqualAndCancelAtPeriodEndTrue(now)) {
            String key = s.hasBillingKey() && cipher.isEnabled() ? cipher.decrypt(s.getBillingKeyEncrypted()) : null;
            s.downgradeToFree();
            if (key != null) portOne.deleteBillingKey(key);
            handled++;
        }
        if (!portOneProperties.isReady() || !cipher.isEnabled()) return handled;
        for (MemberSubscription s : subscriptions.findByNextBillingAtLessThanEqualAndGrantedByAdminFalse(now)) {
            if (!s.isPro() || s.isCancelAtPeriodEnd() || !s.hasBillingKey()) continue;
            renew(s, now);
            handled++;
        }
        return handled;
    }

    /** 다음 한 달 결제. 결제가 밀렸던 경우에도 새 기간은 결제한 시점부터 시작한다. */
    void renew(MemberSubscription s, Instant now) {
        Instant start = s.getStatus() == MemberSubscription.Status.PAST_DUE || s.getPeriodEnd() == null
                ? now : s.getPeriodEnd();
        Instant end = plusOneMonth(start);
        BillingPayment payment = charge(s.getMemberId(), cipher.decrypt(s.getBillingKeyEncrypted()), start, end, s.getCardSummary());
        if (payment.getStatus() == BillingPayment.Status.PAID) {
            s.startProPeriod(start, end);
            return;
        }
        if (s.getFailedAttempts() + 1 >= MAX_FAILED_ATTEMPTS) {
            log.warn("정기결제 {}회 실패로 무료로 전환 | memberId: {}", MAX_FAILED_ATTEMPTS, s.getMemberId());
            s.downgradeToFree();
        } else {
            s.markPaymentFailed(now.plus(Duration.ofDays(1)));
        }
    }

    /** PortOne 웹훅: 본문은 믿지 않고 결제를 다시 조회해 상태를 맞춘다. */
    @Transactional
    public void syncPayment(String paymentId) {
        payments.findByPaymentId(paymentId).ifPresent(p -> {
            JsonNode remote = portOne.getPayment(paymentId);
            String status = remote.path("status").asText("");
            switch (status) {
                case "PAID" -> p.markPaid(parseInstant(remote.path("paidAt").asText(null)),
                        remote.path("receiptUrl").asText(null), cardFromPayment(remote));
                case "FAILED" -> p.markFailed(remote.path("failure").path("reason").asText("결제에 실패했습니다"));
                case "CANCELLED", "PARTIAL_CANCELLED" -> p.markCancelled();
                default -> log.info("웹훅 결제 상태 무시 | paymentId: {}, status: {}", paymentId, status);
            }
        });
    }

    /** 빌링키가 이 회원 것이고 발급 완료 상태인지 확인하고, 카드 요약을 돌려준다. */
    private String verifyBillingKey(Long memberId, String billingKey) {
        if (billingKey == null || billingKey.isBlank()) throw new IllegalArgumentException("카드 등록 정보가 없습니다");
        JsonNode info;
        try {
            info = portOne.getBillingKey(billingKey.trim());
        } catch (PortOneException e) {
            throw new IllegalArgumentException("카드 등록을 확인하지 못했습니다: " + e.getMessage());
        }
        if (!"ISSUED".equals(info.path("status").asText())) {
            throw new IllegalArgumentException("카드 등록이 끝나지 않았습니다");
        }
        String owner = info.path("customer").path("id").asText("");
        if (!customerId(memberId).equals(owner)) {
            throw new IllegalArgumentException("다른 사용자의 카드 등록 정보입니다");
        }
        return cardFromBillingKey(info);
    }

    /** 결제 기록을 먼저 남기고(PENDING) PortOne에 결제를 요청한다. 실패해도 기록은 남는다. */
    private BillingPayment charge(Long memberId, String billingKey, Instant start, Instant end, String card) {
        long amount = usageProperties.plan(PlanType.PRO).getPriceKrw();
        String paymentId = "tmr-" + memberId + "-" + PAYMENT_ID_TIME.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        ZoneId zone = ZoneId.of(usageProperties.getZone());
        String orderName = "티미룸 프로 " + LocalDate.ofInstant(start, zone) + " ~ " + LocalDate.ofInstant(end, zone).minusDays(1);
        BillingPayment payment = payments.save(BillingPayment.pending(memberId, paymentId, PlanType.PRO, amount,
                orderName, start, end, card));
        String email = members.findById(memberId).map(m -> m.getEmail()).orElse(null);
        try {
            JsonNode result = portOne.payWithBillingKey(paymentId, billingKey, orderName, amount, customerId(memberId), email);
            Instant paidAt = parseInstant(result.path("payment").path("paidAt").asText(null));
            String receipt = null;
            String paidCard = null;
            try {
                JsonNode detail = portOne.getPayment(paymentId);
                receipt = detail.path("receiptUrl").asText(null);
                paidCard = cardFromPayment(detail);
                if (paidAt == null) paidAt = parseInstant(detail.path("paidAt").asText(null));
            } catch (PortOneException e) {
                log.warn("결제 상세 조회 실패(영수증은 웹훅으로 채움) | paymentId: {}", paymentId);
            }
            payment.markPaid(paidAt, receipt, paidCard);
        } catch (PortOneException e) {
            payment.markFailed(e.getMessage());
        }
        return payment;
    }

    private void requireReady() {
        if (!portOneProperties.isReady() || !cipher.isEnabled()) {
            throw new IllegalStateException("결제 기능이 아직 준비되지 않았습니다");
        }
    }

    private Instant plusOneMonth(Instant from) {
        ZoneId zone = ZoneId.of(usageProperties.getZone());
        return from.atZone(zone).plusMonths(1).toInstant();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** 빌링키 정보의 카드: methods[0].card 또는 method.card */
    static String cardFromBillingKey(JsonNode info) {
        JsonNode card = info.path("methods").isArray() && info.path("methods").size() > 0
                ? info.path("methods").get(0).path("card") : info.path("method").path("card");
        return cardSummary(card);
    }

    static String cardFromPayment(JsonNode payment) {
        return cardSummary(payment.path("method").path("card"));
    }

    static String cardSummary(JsonNode card) {
        if (card == null || card.isMissingNode() || card.isEmpty()) return null;
        String name = card.path("name").asText("");
        if (name.isBlank()) name = card.path("publisher").asText(card.path("issuer").asText("카드"));
        String number = card.path("number").asText("");
        String digits = number.replaceAll("[^0-9]", "");
        String tail = digits.length() >= 4 ? digits.substring(digits.length() - 4) : "";
        String summary = tail.isEmpty() ? name : name + " ····" + tail;
        return summary.length() > 80 ? summary.substring(0, 80) : summary;
    }

    /** 관리자: 결제 없이 프로를 준다(정기결제 없음) 또는 무료로 돌린다. */
    @Transactional
    public Map<String, Object> adminSetPlan(Long memberId, PlanType plan, int months) {
        MemberSubscription s = subscriptions.findById(memberId).orElseGet(() -> MemberSubscription.free(memberId));
        if (plan == PlanType.PRO) {
            Instant now = Instant.now();
            Instant end = now.atZone(ZoneId.of(usageProperties.getZone())).plusMonths(Math.max(1, Math.min(months, 12))).toInstant();
            s.grantPro(now, end);
        } else {
            String key = s.hasBillingKey() && cipher.isEnabled() ? cipher.decrypt(s.getBillingKeyEncrypted()) : null;
            s.downgradeToFree();
            if (key != null) portOne.deleteBillingKey(key);
        }
        return view(subscriptions.save(s));
    }
}
