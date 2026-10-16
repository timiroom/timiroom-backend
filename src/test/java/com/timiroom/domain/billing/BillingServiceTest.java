package com.timiroom.domain.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.aikey.service.SecretCipher;
import com.timiroom.domain.billing.entity.BillingPayment;
import com.timiroom.domain.billing.repository.BillingPaymentRepository;
import com.timiroom.domain.billing.service.BillingService;
import com.timiroom.domain.member.repository.MemberRepository;
import com.timiroom.domain.usage.UsageProperties;
import com.timiroom.domain.usage.entity.MemberSubscription;
import com.timiroom.domain.usage.enums.PlanType;
import com.timiroom.domain.usage.repository.MemberSubscriptionRepository;
import com.timiroom.infra.portone.PortOneClient;
import com.timiroom.infra.portone.PortOneClient.PortOneException;
import com.timiroom.infra.portone.PortOneProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private BillingService service;

    @BeforeEach
    void setUp() {
        portOne = mock(PortOneClient.class);
        subscriptions = mock(MemberSubscriptionRepository.class);
        payments = mock(BillingPaymentRepository.class);
        when(payments.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(subscriptions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PortOneProperties properties = new PortOneProperties();
        properties.setEnabled(true);
        properties.setStoreId("store-test");
        properties.setChannelKey("channel-key-test");
        properties.setApiSecret("secret");
        cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        service = new BillingService(portOne, properties, new UsageProperties(), subscriptions, payments,
                mock(MemberRepository.class), cipher);
    }

    private JsonNode node(String text) throws Exception {
        return json.readTree(text);
    }

    private void billingKeyOwnedBy(long memberId) throws Exception {
        when(portOne.getBillingKey("bk-1")).thenReturn(node("""
                {"status":"ISSUED","customer":{"id":"timiroom-member-%d"},
                 "methods":[{"card":{"name":"신한카드","number":"1234-****-****-5678"}}]}""".formatted(memberId)));
    }

    @Test
    void 구독하면_첫_달을_결제하고_프로가_된다() throws Exception {
        billingKeyOwnedBy(1);
        when(subscriptions.findById(1L)).thenReturn(Optional.empty());
        when(portOne.payWithBillingKey(anyString(), eq("bk-1"), anyString(), eq(9900L), eq("timiroom-member-1"), any()))
                .thenReturn(node("{\"payment\":{\"paidAt\":\"2026-10-16T10:00:00Z\"}}"));
        when(portOne.getPayment(anyString())).thenReturn(node("{\"status\":\"PAID\",\"receiptUrl\":\"https://receipt.test/1\"}"));

        Map<String, Object> view = service.subscribe(1L, "bk-1");

        assertThat(view.get("plan")).isEqualTo("PRO");
        assertThat(view.get("cardSummary")).isEqualTo("신한카드 ····5678");
        verify(payments, atLeastOnce()).save(argThat(p -> p.getAmountKrw() == 9900 && p.getPlan() == PlanType.PRO));
    }

    @Test
    void 다른_사람의_빌링키는_거절한다() throws Exception {
        billingKeyOwnedBy(2);
        when(subscriptions.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.subscribe(1L, "bk-1")).hasMessageContaining("다른 사용자");
        verify(portOne, never()).payWithBillingKey(any(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void 첫_결제가_실패하면_프로가_되지_않고_빌링키를_지운다() throws Exception {
        billingKeyOwnedBy(1);
        when(subscriptions.findById(1L)).thenReturn(Optional.empty());
        when(portOne.payWithBillingKey(any(), any(), any(), anyLong(), any(), any())).thenThrow(new PortOneException("한도 초과"));

        assertThatThrownBy(() -> service.subscribe(1L, "bk-1")).hasMessageContaining("한도 초과");
        verify(portOne).deleteBillingKey("bk-1");
        verify(subscriptions, never()).save(any());
    }

    @Test
    void 정기결제가_실패하면_다음날_다시_하고_세번째에_무료로_돌린다() {
        MemberSubscription s = MemberSubscription.free(1L);
        Instant now = Instant.now();
        s.startProPeriod(now.minus(Duration.ofDays(30)), now.minusSeconds(60));
        s.changeCard(cipher.encrypt("bk-1"), "카드");
        when(subscriptions.findByNextBillingAtLessThanEqualAndGrantedByAdminFalse(any())).thenReturn(List.of(s));
        when(portOne.payWithBillingKey(any(), any(), any(), anyLong(), any(), any())).thenThrow(new PortOneException("잔액 부족"));

        service.runDueBilling(now);
        assertThat(s.getStatus()).isEqualTo(MemberSubscription.Status.PAST_DUE);
        assertThat(s.getNextBillingAt()).isAfter(now);
        service.runDueBilling(now);
        assertThat(s.getFailedAttempts()).isEqualTo(2);
        service.runDueBilling(now);
        assertThat(s.getPlan()).isEqualTo(PlanType.FREE);
        assertThat(s.hasBillingKey()).isFalse();
    }

    @Test
    void 정기결제가_성공하면_기간이_한_달_이어진다() throws Exception {
        MemberSubscription s = MemberSubscription.free(1L);
        Instant end = Instant.now().minusSeconds(60);
        s.startProPeriod(end.minus(Duration.ofDays(30)), end);
        s.changeCard(cipher.encrypt("bk-1"), "카드");
        when(subscriptions.findByNextBillingAtLessThanEqualAndGrantedByAdminFalse(any())).thenReturn(List.of(s));
        when(portOne.payWithBillingKey(any(), any(), any(), anyLong(), any(), any())).thenReturn(node("{}"));
        when(portOne.getPayment(any())).thenReturn(node("{\"status\":\"PAID\"}"));

        service.runDueBilling(Instant.now());

        assertThat(s.getPeriodStart()).isEqualTo(end);
        assertThat(s.getPeriodEnd()).isAfter(end.plus(Duration.ofDays(27)));
        assertThat(s.getStatus()).isEqualTo(MemberSubscription.Status.ACTIVE);
    }

    @Test
    void 해지_예약은_기간이_끝나면_무료로_돌린다() {
        MemberSubscription s = MemberSubscription.free(1L);
        Instant now = Instant.now();
        s.startProPeriod(now.minus(Duration.ofDays(30)), now.minusSeconds(1));
        s.changeCard(cipher.encrypt("bk-1"), "카드");
        s.scheduleCancel(true);
        when(subscriptions.findByPeriodEndLessThanEqualAndCancelAtPeriodEndTrue(any())).thenReturn(List.of(s));

        service.runDueBilling(now);

        assertThat(s.getPlan()).isEqualTo(PlanType.FREE);
        verify(portOne).deleteBillingKey("bk-1");
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
