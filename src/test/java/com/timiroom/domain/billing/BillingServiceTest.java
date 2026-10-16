package com.timiroom.domain.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.aikey.service.SecretCipher;
import com.timiroom.domain.billing.entity.BillingPayment;
import com.timiroom.domain.billing.entity.PaymentMethod;
import com.timiroom.domain.billing.repository.BillingPaymentRepository;
import com.timiroom.domain.billing.repository.PaymentMethodRepository;
import com.timiroom.domain.billing.service.BillingService;
import com.timiroom.domain.billing.service.BillingService.PaySource;
import com.timiroom.domain.member.repository.MemberRepository;
import com.timiroom.domain.usage.UsageProperties;
import com.timiroom.domain.usage.entity.MemberSubscription;
import com.timiroom.domain.usage.enums.PlanType;
import com.timiroom.domain.usage.repository.MemberSubscriptionRepository;
import com.timiroom.domain.usage.service.UsageService;
import com.timiroom.infra.portone.PortOneClient;
import com.timiroom.infra.portone.PortOneClient.PortOneException;
import com.timiroom.infra.portone.PortOneProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BillingServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private PortOneClient portOne;
    private MemberSubscriptionRepository subscriptions;
    private BillingPaymentRepository payments;
    private SecretCipher cipher;
    private UsageService usageService;
    private BillingService service;
    /** 메모리 위 결제 수단 저장소 */
    private final List<PaymentMethod> methods = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        portOne = mock(PortOneClient.class);
        subscriptions = mock(MemberSubscriptionRepository.class);
        payments = mock(BillingPaymentRepository.class);
        usageService = mock(UsageService.class);
        when(payments.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(subscriptions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(subscriptions.findById(any())).thenReturn(Optional.empty());

        PaymentMethodRepository methodRepo = mock(PaymentMethodRepository.class);
        when(methodRepo.save(any())).thenAnswer(inv -> {
            PaymentMethod m = inv.getArgument(0);
            var id = PaymentMethod.class.getDeclaredField("id");
            id.setAccessible(true);
            if (id.get(m) == null) id.set(m, (long) methods.size() + 1);
            methods.add(m);
            return m;
        });
        when(methodRepo.findByMemberIdOrderByCreatedAtAsc(anyLong())).thenAnswer(inv -> List.copyOf(methods));
        when(methodRepo.findFirstByMemberIdAndDefaultMethodTrue(anyLong()))
                .thenAnswer(inv -> methods.stream().filter(PaymentMethod::isDefaultMethod).findFirst());
        when(methodRepo.findByIdAndMemberId(anyLong(), anyLong()))
                .thenAnswer(inv -> methods.stream().filter(m -> m.getId().equals(inv.getArgument(0))).findFirst());
        when(methodRepo.countByMemberId(anyLong())).thenAnswer(inv -> (long) methods.size());
        doAnswer(inv -> methods.remove((PaymentMethod) inv.getArgument(0))).when(methodRepo).delete(any());

        PortOneProperties properties = new PortOneProperties();
        properties.setEnabled(true);
        properties.setStoreId("store-test");
        properties.setChannelKey("channel-key-test");
        properties.setApiSecret("secret");
        cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        service = new BillingService(portOne, properties, new UsageProperties(), subscriptions, payments, methodRepo,
                mock(MemberRepository.class), cipher, usageService);
    }

    private JsonNode node(String text) throws Exception {
        return json.readTree(text);
    }

    private void billingKey(String key, long owner, String card) throws Exception {
        when(portOne.getBillingKey(key)).thenReturn(node("""
                {"status":"ISSUED","customer":{"id":"timiroom-member-%d"},
                 "methods":[{"card":{"name":"%s","number":"1234-****-****-5678"}}]}""".formatted(owner, card)));
    }

    private void paymentsSucceed() throws Exception {
        when(portOne.payWithBillingKey(anyString(), anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(node("{\"payment\":{\"paidAt\":\"2026-10-16T10:00:00Z\"}}"));
        when(portOne.getPayment(anyString())).thenReturn(node("{\"status\":\"PAID\",\"receiptUrl\":\"https://receipt.test/1\"}"));
    }

    @Test
    void 새_카드로_구독하면_카드가_기본으로_등록되고_프로가_된다() throws Exception {
        billingKey("bk-1", 1, "신한카드");
        paymentsSucceed();

        Map<String, Object> view = service.subscribe(1L, new PaySource(null, "bk-1"));

        assertThat(view.get("plan")).isEqualTo("PRO");
        assertThat(view.get("defaultCard")).isEqualTo("신한카드 ····5678");
        assertThat(methods).hasSize(1);
        verify(payments, atLeastOnce()).save(argThat(p -> p.getAmountKrw() == 9900 && p.getKind() == BillingPayment.Kind.SUBSCRIPTION));
    }

    @Test
    void 다른_사람의_빌링키는_등록하지_않는다() throws Exception {
        billingKey("bk-1", 2, "신한카드");
        assertThatThrownBy(() -> service.addPaymentMethod(1L, "bk-1", false)).hasMessageContaining("다른 사용자");
        assertThat(methods).isEmpty();
    }

    @Test
    void 카드를_여러_장_등록하고_기본_카드를_바꿀_수_있다() throws Exception {
        billingKey("bk-1", 1, "신한카드");
        billingKey("bk-2", 1, "현대카드");
        service.addPaymentMethod(1L, "bk-1", false);
        List<Map<String, Object>> views = service.addPaymentMethod(1L, "bk-2", false);
        assertThat(views).extracting(v -> v.get("isDefault")).containsExactly(true, false);

        views = service.setDefault(1L, methods.get(1).getId());
        assertThat(views).extracting(v -> v.get("isDefault")).containsExactly(false, true);
    }

    @Test
    void 정기결제_중이면_마지막_카드는_지울_수_없다() throws Exception {
        billingKey("bk-1", 1, "신한카드");
        paymentsSucceed();
        service.subscribe(1L, new PaySource(null, "bk-1"));
        MemberSubscription pro = MemberSubscription.free(1L);
        pro.startProPeriod(Instant.now(), Instant.now().plus(Duration.ofDays(30)));
        when(subscriptions.findById(1L)).thenReturn(Optional.of(pro));

        assertThatThrownBy(() -> service.deletePaymentMethod(1L, methods.get(0).getId())).hasMessageContaining("정기결제");
    }

    @Test
    void 기본_카드를_지우면_남은_카드가_기본이_된다() throws Exception {
        billingKey("bk-1", 1, "신한카드");
        billingKey("bk-2", 1, "현대카드");
        service.addPaymentMethod(1L, "bk-1", false);
        service.addPaymentMethod(1L, "bk-2", false);

        List<Map<String, Object>> views = service.deletePaymentMethod(1L, methods.get(0).getId());

        assertThat(views).hasSize(1);
        assertThat(views.get(0).get("isDefault")).isEqualTo(true);
        verify(portOne).deleteBillingKey("bk-1");
    }

    @Test
    void 첫_결제가_실패하면_프로가_되지_않는다() throws Exception {
        billingKey("bk-1", 1, "신한카드");
        when(portOne.payWithBillingKey(any(), any(), any(), anyLong(), any(), any())).thenThrow(new PortOneException("한도 초과"));

        assertThatThrownBy(() -> service.subscribe(1L, new PaySource(null, "bk-1"))).hasMessageContaining("한도 초과");
        verify(subscriptions, never()).save(any());
    }

    @Test
    void 추가_사용량을_사면_잔액이_충전된다() throws Exception {
        billingKey("bk-1", 1, "신한카드");
        paymentsSucceed();
        service.addPaymentMethod(1L, "bk-1", false);
        when(usageService.statusOf(1L)).thenReturn(new UsageService.Status(PlanType.FREE, "무료", 3000,
                BigDecimal.ZERO, BigDecimal.valueOf(3000), 0, BigDecimal.ZERO, new UsageService.Period(Instant.now(), Instant.now())));

        service.buyCredit(1L, 10_000L, new PaySource(null, null));

        verify(usageService).addCredit(1L, new BigDecimal("10000"));
        verify(payments, atLeastOnce()).save(argThat(p -> p.getKind() == BillingPayment.Kind.CREDIT && p.getAmountKrw() == 10_000));
    }

    @Test
    void 충전_금액은_정한_범위와_천원_단위만_받는다() throws Exception {
        billingKey("bk-1", 1, "신한카드");
        service.addPaymentMethod(1L, "bk-1", false);
        assertThatThrownBy(() -> service.buyCredit(1L, 4_000L, null)).hasMessageContaining("1,000원 단위");
        assertThatThrownBy(() -> service.buyCredit(1L, 10_500L, null)).hasMessageContaining("1,000원 단위");
        verify(portOne, never()).payWithBillingKey(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void 결제가_실패하면_추가_사용량을_충전하지_않는다() throws Exception {
        billingKey("bk-1", 1, "신한카드");
        service.addPaymentMethod(1L, "bk-1", false);
        when(portOne.payWithBillingKey(any(), any(), any(), anyLong(), any(), any())).thenThrow(new PortOneException("잔액 부족"));

        assertThatThrownBy(() -> service.buyCredit(1L, 5_000L, null)).hasMessageContaining("잔액 부족");
        verify(usageService, never()).addCredit(anyLong(), any());
    }

    @Test
    void 정기결제가_실패하면_다음날_다시_하고_세번째에_무료로_돌린다() throws Exception {
        billingKey("bk-1", 1, "신한카드");
        service.addPaymentMethod(1L, "bk-1", false);
        MemberSubscription s = MemberSubscription.free(1L);
        Instant now = Instant.now();
        s.startProPeriod(now.minus(Duration.ofDays(30)), now.minusSeconds(60));
        when(subscriptions.findByNextBillingAtLessThanEqualAndGrantedByAdminFalse(any())).thenReturn(List.of(s));
        when(portOne.payWithBillingKey(any(), any(), any(), anyLong(), any(), any())).thenThrow(new PortOneException("잔액 부족"));

        service.runDueBilling(now);
        assertThat(s.getStatus()).isEqualTo(MemberSubscription.Status.PAST_DUE);
        service.runDueBilling(now);
        service.runDueBilling(now);
        assertThat(s.getPlan()).isEqualTo(PlanType.FREE);
    }

    @Test
    void 정기결제가_성공하면_기간이_한_달_이어진다() throws Exception {
        billingKey("bk-1", 1, "신한카드");
        paymentsSucceed();
        service.addPaymentMethod(1L, "bk-1", false);
        MemberSubscription s = MemberSubscription.free(1L);
        Instant end = Instant.now().minusSeconds(60);
        s.startProPeriod(end.minus(Duration.ofDays(30)), end);
        when(subscriptions.findByNextBillingAtLessThanEqualAndGrantedByAdminFalse(any())).thenReturn(List.of(s));

        service.runDueBilling(Instant.now());

        assertThat(s.getPeriodStart()).isEqualTo(end);
        assertThat(s.getPeriodEnd()).isAfter(end.plus(Duration.ofDays(27)));
    }

    @Test
    void 해지_예약은_기간이_끝나면_무료로_돌린다() {
        MemberSubscription s = MemberSubscription.free(1L);
        Instant now = Instant.now();
        s.startProPeriod(now.minus(Duration.ofDays(30)), now.minusSeconds(1));
        s.scheduleCancel(true);
        when(subscriptions.findByPeriodEndLessThanEqualAndCancelAtPeriodEndTrue(any())).thenReturn(List.of(s));

        service.runDueBilling(now);

        assertThat(s.getPlan()).isEqualTo(PlanType.FREE);
        verify(portOne, never()).payWithBillingKey(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void 웹훅은_결제를_다시_조회해_영수증을_채운다() throws Exception {
        BillingPayment payment = BillingPayment.pending(1L, "p-1", PlanType.PRO, 9900, "티미룸 프로", null, null, null);
        when(payments.findByPaymentId("p-1")).thenReturn(Optional.of(payment));
        when(portOne.getPayment("p-1")).thenReturn(node("""
                {"status":"PAID","paidAt":"2026-10-16T10:00:00Z","receiptUrl":"https://r.test",
                 "method":{"card":{"name":"현대카드","number":"9999-****-****-4321"}}}"""));

        service.syncPayment("p-1");

        assertThat(payment.getStatus()).isEqualTo(BillingPayment.Status.PAID);
        assertThat(payment.getReceiptUrl()).isEqualTo("https://r.test");
        assertThat(payment.getCardSummary()).isEqualTo("현대카드 ····4321");
    }
}
