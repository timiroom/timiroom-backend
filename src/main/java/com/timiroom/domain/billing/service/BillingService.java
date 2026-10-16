package com.timiroom.domain.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.timiroom.domain.aikey.service.SecretCipher;
import com.timiroom.domain.billing.entity.BillingPayment;
import com.timiroom.domain.billing.entity.PaymentMethod;
import com.timiroom.domain.billing.repository.BillingPaymentRepository;
import com.timiroom.domain.billing.repository.PaymentMethodRepository;
import com.timiroom.domain.member.repository.MemberRepository;
import com.timiroom.domain.usage.UsageProperties;
import com.timiroom.domain.usage.entity.MemberSubscription;
import com.timiroom.domain.usage.enums.PlanType;
import com.timiroom.domain.usage.repository.MemberSubscriptionRepository;
import com.timiroom.domain.usage.service.UsageService;
import com.timiroom.infra.portone.PortOneClient;
import com.timiroom.infra.portone.PortOneClient.PortOneException;
import com.timiroom.infra.portone.PortOneProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 결제(PortOne V2 빌링키).
 *   결제 수단: 카드를 여러 장 등록하고 하나를 기본으로 둔다. 정기결제와 추가 사용량 구매는 기본 카드로 한다.
 *   구독: 기본 카드(없으면 새로 등록한 카드)로 첫 달 결제 → 프로 시작.
 *   갱신: 스케줄러가 매일 결제일이 지난 구독을 기본 카드로 결제한다. 실패하면 하루 뒤 다시, 3번 실패하면 무료로 돌린다.
 *   해지: 기간 끝에 무료로 돌린다(남은 기간은 그대로 쓴다).
 *   추가 사용량: 상품을 골라 한 번 결제하면 잔액이 충전되고, 요금제 한도를 다 쓴 뒤부터 차감된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    static final int MAX_FAILED_ATTEMPTS = 3;
    static final int MAX_PAYMENT_METHODS = 5;
    private static final DateTimeFormatter PAYMENT_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final PortOneClient portOne;
    private final PortOneProperties portOneProperties;
    private final UsageProperties usageProperties;
    private final MemberSubscriptionRepository subscriptions;
    private final BillingPaymentRepository payments;
    private final PaymentMethodRepository paymentMethods;
    private final MemberRepository members;
    private final SecretCipher cipher;
    private final UsageService usageService;

    /** 결제 대상: 등록된 카드 id 또는 방금 결제창에서 받은 빌링키 중 하나 */
    public record PaySource(Long paymentMethodId, String billingKey) {}

    /** 브라우저가 PortOne 결제창을 열 때 쓰는 공개 값 */
    public Map<String, Object> clientConfig() {
        boolean ready = isReady();
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

    // ── 요금제 ─────────────────────────────────────────────

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
        view.put("grantedByAdmin", s.isGrantedByAdmin());
        view.put("failedAttempts", s.getFailedAttempts());
        view.put("priceKrw", usageProperties.plan(PlanType.PRO).getPriceKrw());
        view.put("customerId", customerId(memberId));
        view.put("defaultCard", paymentMethods.findFirstByMemberIdAndDefaultMethodTrue(memberId)
                .map(PaymentMethod::getCardSummary).orElse(null));
        return view;
    }

    /** 첫 구독: 카드 확인 → 첫 달 결제 → 프로 시작. 결제 실패 기록은 남기려고 안내 예외에서 되돌리지 않는다. */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public Map<String, Object> subscribe(Long memberId, PaySource source) {
        requireReady();
        MemberSubscription s = subscriptions.findById(memberId).orElseGet(() -> MemberSubscription.free(memberId));
        Instant now = Instant.now();
        if (s.isPro() && s.getPeriodEnd() != null && now.isBefore(s.getPeriodEnd()) && !s.isGrantedByAdmin()) {
            throw new IllegalArgumentException("이미 프로 요금제를 쓰고 있습니다");
        }
        PaymentMethod method = resolve(memberId, source);
        Instant end = plusOneMonth(now);
        BillingPayment payment = chargeSubscription(memberId, method, now, end);
        if (payment.getStatus() != BillingPayment.Status.PAID) {
            throw new IllegalArgumentException(failure(payment));
        }
        s.startProPeriod(now, end);
        s.scheduleCancel(false);
        return view(subscriptions.save(s));
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
        if (s.isGrantedByAdmin() || paymentMethods.findFirstByMemberIdAndDefaultMethodTrue(memberId).isEmpty()) {
            throw new IllegalArgumentException("결제 수단이 없어 이어 쓸 수 없습니다");
        }
        if (s.getPeriodEnd() != null && !Instant.now().isBefore(s.getPeriodEnd())) {
            throw new IllegalArgumentException("이미 기간이 끝났습니다. 다시 구독해 주세요");
        }
        s.scheduleCancel(false);
        return view(s);
    }

    // ── 결제 수단 ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> paymentMethodViews(Long memberId) {
        return paymentMethods.findByMemberIdOrderByCreatedAtAsc(memberId).stream().map(m -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", m.getId());
            view.put("cardSummary", m.getCardSummary() != null ? m.getCardSummary() : "카드");
            view.put("isDefault", m.isDefaultMethod());
            view.put("createdAt", m.getCreatedAt());
            return view;
        }).toList();
    }

    /** 카드 추가. 첫 카드이거나 makeDefault면 기본 카드가 된다. 결제가 밀려 있으면 새 기본 카드로 바로 다시 결제한다. */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public List<Map<String, Object>> addPaymentMethod(Long memberId, String billingKey, boolean makeDefault) {
        requireReady();
        PaymentMethod added = register(memberId, billingKey, makeDefault);
        retryIfPastDue(memberId, added);
        return paymentMethodViews(memberId);
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public List<Map<String, Object>> setDefault(Long memberId, Long id) {
        PaymentMethod target = owned(memberId, id);
        makeDefault(memberId, target);
        retryIfPastDue(memberId, target);
        return paymentMethodViews(memberId);
    }

    /** 카드 삭제. 정기결제 중인데 남는 카드가 없으면 지울 수 없다. 기본 카드를 지우면 가장 먼저 등록한 카드가 기본이 된다. */
    @Transactional
    public List<Map<String, Object>> deletePaymentMethod(Long memberId, Long id) {
        PaymentMethod target = owned(memberId, id);
        List<PaymentMethod> all = paymentMethods.findByMemberIdOrderByCreatedAtAsc(memberId);
        boolean renewing = subscriptions.findById(memberId)
                .map(s -> s.isPro() && !s.isCancelAtPeriodEnd() && !s.isGrantedByAdmin()).orElse(false);
        if (renewing && all.size() == 1) {
            throw new IllegalArgumentException("정기결제에 쓰는 카드입니다. 다른 카드를 먼저 추가하거나 구독을 해지해 주세요");
        }
        paymentMethods.delete(target);
        if (target.isDefaultMethod()) {
            all.stream().filter(m -> !m.getId().equals(target.getId())).findFirst().ifPresent(m -> m.setDefaultMethod(true));
        }
        deleteRemoteKey(target);
        return paymentMethodViews(memberId);
    }

    // ── 추가 사용량 ─────────────────────────────────────────

    /** 추가 사용량 잔액(원)과 충전 금액 선택지 */
    @Transactional(readOnly = true)
    public Map<String, Object> creditView(Long memberId) {
        UsageService.Status status = usageService.statusOf(memberId);
        UsageService.Credit credit = status.credit();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("balanceKrw", credit.balanceKrw().setScale(0, RoundingMode.DOWN).longValue());
        view.put("enabled", credit.enabled());
        view.put("monthlyCapKrw", credit.monthlyCapKrw());
        view.put("spentKrw", credit.spentKrw().longValue());
        view.put("resetAt", status.period().end());
        view.put("presetsKrw", usageProperties.getCreditPresetsKrw());
        view.put("minKrw", usageProperties.getCreditMinKrw());
        view.put("maxKrw", usageProperties.getCreditMaxKrw());
        view.put("paymentReady", isReady());
        return view;
    }

    @Transactional
    public Map<String, Object> configureCredit(Long memberId, boolean enabled, Long monthlyCapKrw) {
        usageService.configureCredit(memberId, enabled, monthlyCapKrw);
        return creditView(memberId);
    }

    /** 원하는 금액만큼 충전한다(1,000원 단위). 결제가 끝나야 잔액이 늘어난다. */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public Map<String, Object> buyCredit(Long memberId, Long amountKrw, PaySource source) {
        requireReady();
        long amount = amountKrw == null ? 0 : amountKrw;
        if (amount < usageProperties.getCreditMinKrw() || amount > usageProperties.getCreditMaxKrw() || amount % 1000 != 0) {
            throw new IllegalArgumentException(String.format("충전 금액은 %,d원부터 %,d원까지 1,000원 단위로 정할 수 있습니다",
                    usageProperties.getCreditMinKrw(), usageProperties.getCreditMaxKrw()));
        }
        PaymentMethod method = resolve(memberId, source);
        BigDecimal credit = BigDecimal.valueOf(amount);
        BillingPayment payment = payments.save(BillingPayment.pendingCredit(memberId, newPaymentId(memberId), credit,
                amount, "티미룸 추가 사용량 충전", method.getCardSummary()));
        pay(payment, method);
        if (payment.getStatus() != BillingPayment.Status.PAID) {
            throw new IllegalArgumentException(failure(payment));
        }
        usageService.addCredit(memberId, credit);
        return creditView(memberId);
    }

    // ── 결제 내역 ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<BillingPayment> history(Long memberId, int page, int size) {
        return payments.findByMemberIdOrderByCreatedAtDesc(memberId, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)));
    }

    /** 영수증 화면 데이터. PortOne 영수증 주소가 없으면 화면이 자체 결제 확인서를 그린다. */
    @Transactional(readOnly = true)
    public Map<String, Object> receipt(Long memberId, Long id) {
        BillingPayment p = payments.findByIdAndMemberId(id, memberId)
                .orElseThrow(() -> new IllegalArgumentException("결제 내역을 찾을 수 없습니다"));
        var member = members.findById(memberId).orElse(null);
        Map<String, Object> view = paymentView(p);
        // member_name은 로그인 제공자 식별값(google_…)이라 화면 이름인 nickname을 먼저 쓴다.
        view.put("buyerName", member != null ? Optional.ofNullable(member.getNickname()).orElse(member.getMemberName()) : null);
        view.put("buyerEmail", member != null ? member.getEmail() : null);
        view.put("supplyKrw", Math.round(p.getAmountKrw() / 1.1));
        view.put("vatKrw", p.getAmountKrw() - Math.round(p.getAmountKrw() / 1.1));
        return view;
    }

    public static Map<String, Object> paymentView(BillingPayment p) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", p.getId());
        view.put("paymentId", p.getPaymentId());
        view.put("kind", p.getKind().name());
        view.put("plan", p.getPlan() != null ? p.getPlan().name() : null);
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

    // ── 스케줄러·웹훅 ────────────────────────────────────────

    /** 스케줄러: 기간이 끝난 해지 예약을 무료로 돌리고, 결제일이 된 구독을 갱신한다. */
    @Transactional
    public int runDueBilling(Instant now) {
        int handled = 0;
        for (MemberSubscription s : subscriptions.findByPeriodEndLessThanEqualAndCancelAtPeriodEndTrue(now)) {
            s.downgradeToFree();
            handled++;
        }
        if (!isReady()) return handled;
        for (MemberSubscription s : subscriptions.findByNextBillingAtLessThanEqualAndGrantedByAdminFalse(now)) {
            if (!s.isPro() || s.isCancelAtPeriodEnd()) continue;
            Optional<PaymentMethod> method = paymentMethods.findFirstByMemberIdAndDefaultMethodTrue(s.getMemberId());
            if (method.isEmpty()) {
                fail(s, now);
            } else {
                renew(s, method.get(), now);
            }
            handled++;
        }
        return handled;
    }

    /** 다음 한 달 결제. 결제가 밀렸던 경우에도 새 기간은 결제한 시점부터 시작한다. */
    void renew(MemberSubscription s, PaymentMethod method, Instant now) {
        Instant start = s.getStatus() == MemberSubscription.Status.PAST_DUE || s.getPeriodEnd() == null
                ? now : s.getPeriodEnd();
        Instant end = plusOneMonth(start);
        BillingPayment payment = chargeSubscription(s.getMemberId(), method, start, end);
        if (payment.getStatus() == BillingPayment.Status.PAID) {
            s.startProPeriod(start, end);
        } else {
            fail(s, now);
        }
    }

    private void fail(MemberSubscription s, Instant now) {
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

    /** 관리자: 결제 없이 프로를 준다(정기결제 없음) 또는 무료로 돌린다. */
    @Transactional
    public Map<String, Object> adminSetPlan(Long memberId, PlanType plan, int months) {
        MemberSubscription s = subscriptions.findById(memberId).orElseGet(() -> MemberSubscription.free(memberId));
        if (plan == PlanType.PRO) {
            Instant now = Instant.now();
            Instant end = now.atZone(ZoneId.of(usageProperties.getZone())).plusMonths(Math.max(1, Math.min(months, 12))).toInstant();
            s.grantPro(now, end);
        } else {
            s.downgradeToFree();
        }
        return view(subscriptions.save(s));
    }

    // ── 내부 ────────────────────────────────────────────────

    /** 결제에 쓸 카드: 새 빌링키면 등록(기본 카드로), 아니면 고른 카드, 둘 다 없으면 기본 카드 */
    private PaymentMethod resolve(Long memberId, PaySource source) {
        if (source != null && source.billingKey() != null && !source.billingKey().isBlank()) {
            return register(memberId, source.billingKey(), true);
        }
        if (source != null && source.paymentMethodId() != null) return owned(memberId, source.paymentMethodId());
        return paymentMethods.findFirstByMemberIdAndDefaultMethodTrue(memberId)
                .orElseThrow(() -> new IllegalArgumentException("결제 수단을 먼저 추가해 주세요"));
    }

    private PaymentMethod register(Long memberId, String billingKey, boolean makeDefault) {
        if (paymentMethods.countByMemberId(memberId) >= MAX_PAYMENT_METHODS) {
            throw new IllegalArgumentException("결제 수단은 " + MAX_PAYMENT_METHODS + "개까지 등록할 수 있습니다");
        }
        String card = verifyBillingKey(memberId, billingKey);
        boolean first = paymentMethods.findFirstByMemberIdAndDefaultMethodTrue(memberId).isEmpty();
        PaymentMethod method = paymentMethods.save(PaymentMethod.create(memberId, cipher.encrypt(billingKey.trim()), card, false));
        if (first || makeDefault) makeDefault(memberId, method);
        return method;
    }

    private void makeDefault(Long memberId, PaymentMethod target) {
        paymentMethods.findByMemberIdOrderByCreatedAtAsc(memberId)
                .forEach(m -> m.setDefaultMethod(m.getId().equals(target.getId())));
        target.setDefaultMethod(true);
    }

    private void retryIfPastDue(Long memberId, PaymentMethod method) {
        if (!method.isDefaultMethod()) return;
        subscriptions.findById(memberId)
                .filter(s -> s.isPro() && s.getStatus() == MemberSubscription.Status.PAST_DUE)
                .ifPresent(s -> renew(s, method, Instant.now()));
    }

    private PaymentMethod owned(Long memberId, Long id) {
        return paymentMethods.findByIdAndMemberId(id, memberId)
                .orElseThrow(() -> new IllegalArgumentException("결제 수단을 찾을 수 없습니다"));
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
        if (!customerId(memberId).equals(info.path("customer").path("id").asText(""))) {
            throw new IllegalArgumentException("다른 사용자의 카드 등록 정보입니다");
        }
        return cardFromBillingKey(info);
    }

    /** 결제 기록을 먼저 남기고(PENDING) PortOne에 결제를 요청한다. 실패해도 기록은 남는다. */
    private BillingPayment chargeSubscription(Long memberId, PaymentMethod method, Instant start, Instant end) {
        long amount = usageProperties.plan(PlanType.PRO).getPriceKrw();
        ZoneId zone = ZoneId.of(usageProperties.getZone());
        String orderName = "티미룸 프로 " + LocalDate.ofInstant(start, zone) + " ~ " + LocalDate.ofInstant(end, zone).minusDays(1);
        BillingPayment payment = payments.save(BillingPayment.pending(memberId, newPaymentId(memberId), PlanType.PRO, amount,
                orderName, start, end, method.getCardSummary()));
        pay(payment, method);
        return payment;
    }

    private void pay(BillingPayment payment, PaymentMethod method) {
        String email = members.findById(payment.getMemberId()).map(m -> m.getEmail()).orElse(null);
        try {
            String billingKey = cipher.decrypt(method.getBillingKeyEncrypted());
            JsonNode result = portOne.payWithBillingKey(payment.getPaymentId(), billingKey, payment.getOrderName(),
                    payment.getAmountKrw(), customerId(payment.getMemberId()), email);
            Instant paidAt = parseInstant(result.path("payment").path("paidAt").asText(null));
            String receipt = null;
            String paidCard = null;
            try {
                JsonNode detail = portOne.getPayment(payment.getPaymentId());
                receipt = detail.path("receiptUrl").asText(null);
                paidCard = cardFromPayment(detail);
                if (paidAt == null) paidAt = parseInstant(detail.path("paidAt").asText(null));
            } catch (PortOneException e) {
                log.warn("결제 상세 조회 실패(영수증은 웹훅으로 채움) | paymentId: {}", payment.getPaymentId());
            }
            payment.markPaid(paidAt, receipt, paidCard);
        } catch (PortOneException e) {
            payment.markFailed(e.getMessage());
        }
    }

    private void deleteRemoteKey(PaymentMethod method) {
        try {
            portOne.deleteBillingKey(cipher.decrypt(method.getBillingKeyEncrypted()));
        } catch (RuntimeException e) {
            log.warn("빌링키 정리 실패 | paymentMethodId: {}", method.getId());
        }
    }

    private boolean isReady() {
        return portOneProperties.isReady() && cipher.isEnabled();
    }

    private void requireReady() {
        if (!isReady()) throw new IllegalStateException("결제 기능이 아직 준비되지 않았습니다");
    }

    private static String failure(BillingPayment payment) {
        return payment.getFailureReason() != null ? payment.getFailureReason() : "결제에 실패했습니다";
    }

    private static String newPaymentId(Long memberId) {
        return "tmr-" + memberId + "-" + PAYMENT_ID_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Instant plusOneMonth(Instant from) {
        return from.atZone(ZoneId.of(usageProperties.getZone())).plusMonths(1).toInstant();
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
        String digits = card.path("number").asText("").replaceAll("[^0-9]", "");
        String tail = digits.length() >= 4 ? digits.substring(digits.length() - 4) : "";
        String summary = tail.isEmpty() ? name : name + " ····" + tail;
        return summary.length() > 80 ? summary.substring(0, 80) : summary;
    }
}
