package com.timiroom.domain.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.billing.entity.BillingPayment;
import com.timiroom.domain.billing.repository.BillingPaymentRepository;
import com.timiroom.domain.billing.service.BillingNotifier;
import com.timiroom.domain.billing.service.RefundService;
import com.timiroom.domain.usage.entity.MemberSubscription;
import com.timiroom.domain.usage.enums.PlanType;
import com.timiroom.domain.usage.repository.MemberSubscriptionRepository;
import com.timiroom.domain.usage.repository.UsageEventRepository;
import com.timiroom.domain.usage.service.UsageService;
import com.timiroom.infra.portone.PortOneClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefundServiceTest {

    private static final long MEMBER = 7L;

    private final BillingPaymentRepository payments = mock(BillingPaymentRepository.class);
    private final MemberSubscriptionRepository subscriptions = mock(MemberSubscriptionRepository.class);
    private final UsageEventRepository usageEvents = mock(UsageEventRepository.class);
    private final UsageService usageService = mock(UsageService.class);
    private final PortOneClient portOne = mock(PortOneClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private RefundService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new RefundService(payments, subscriptions, usageEvents, usageService, portOne,
                mock(JdbcTemplate.class), mock(BillingNotifier.class));
        when(portOne.cancelPayment(anyString(), anyLong(), anyLong(), anyString()))
                .thenReturn(json.readTree("{\"status\":\"SUCCEEDED\"}"));
        when(usageService.takeCredit(eq(MEMBER), any())).thenAnswer(inv -> inv.getArgument(1));
    }

    private MemberSubscription proStartedAt(Instant start) {
        MemberSubscription s = MemberSubscription.free(MEMBER);
        s.startProPeriod(start, start.plus(Duration.ofDays(30)));
        when(subscriptions.findById(MEMBER)).thenReturn(Optional.of(s));
        return s;
    }

    private BillingPayment subscriptionPaid(Instant start) {
        BillingPayment p = BillingPayment.pending(MEMBER, "tmr-sub", PlanType.PRO, 9_900, "프로", start,
                start.plus(Duration.ofDays(30)), "카드");
        p.markPaid(start, null, null);
        when(payments.findFirstByMemberIdAndKindAndPeriodStartAndStatusOrderByCreatedAtDesc(
                MEMBER, BillingPayment.Kind.SUBSCRIPTION, start, BillingPayment.Status.PAID)).thenReturn(Optional.of(p));
        return p;
    }

    private BillingPayment creditPaid(String id, long amount, Instant paidAt) {
        BillingPayment p = BillingPayment.pendingCredit(MEMBER, id, BigDecimal.valueOf(amount), amount, "충전", "카드");
        p.markPaid(paidAt, null, null);
        return p;
    }

    @Test
    void 결제_후_7일_안에_쓰지_않았으면_전액_환불하고_무료로_돌린다() {
        Instant start = Instant.now().minus(Duration.ofDays(2));
        MemberSubscription s = proStartedAt(start);
        BillingPayment p = subscriptionPaid(start);
        when(usageEvents.sumCountedCost(eq(MEMBER), eq(start), any())).thenReturn(BigDecimal.ZERO);

        service.refundSubscription(MEMBER);

        verify(portOne).cancelPayment(eq("tmr-sub"), eq(9_900L), eq(9_900L), anyString());
        assertThat(p.getStatus()).isEqualTo(BillingPayment.Status.CANCELLED);
        assertThat(p.getRefundedKrw()).isEqualTo(9_900);
        assertThat(s.isPro()).isFalse();
    }

    @Test
    void 이번_기간에_AI를_썼으면_요금제는_환불하지_않는다() {
        Instant start = Instant.now().minus(Duration.ofDays(2));
        proStartedAt(start);
        subscriptionPaid(start);
        when(usageEvents.sumCountedCost(eq(MEMBER), eq(start), any())).thenReturn(new BigDecimal("35"));

        assertThatThrownBy(() -> service.refundSubscription(MEMBER)).hasMessageContaining("사용해");
        verify(portOne, never()).cancelPayment(anyString(), anyLong(), anyLong(), anyString());
    }

    @Test
    void 결제_후_7일이_지나면_요금제는_환불하지_않는다() {
        Instant start = Instant.now().minus(Duration.ofDays(8));
        proStartedAt(start);
        subscriptionPaid(start);
        when(usageEvents.sumCountedCost(eq(MEMBER), eq(start), any())).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.refundSubscription(MEMBER)).hasMessageContaining("7일");
    }

    @Test
    void 추가_사용량은_남은_잔액만큼_최근_충전부터_취소한다() {
        Instant now = Instant.now();
        BillingPayment recent = creditPaid("tmr-c2", 10_000, now.minus(Duration.ofDays(1)));
        BillingPayment older = creditPaid("tmr-c1", 5_000, now.minus(Duration.ofDays(20)));
        when(payments.findByMemberIdAndKindAndPaidAtAfterOrderByPaidAtDesc(eq(MEMBER), eq(BillingPayment.Kind.CREDIT), any()))
                .thenReturn(List.of(recent, older));
        when(usageService.creditBalance(MEMBER)).thenReturn(new BigDecimal("12000.7"));

        var result = service.refundCredit(MEMBER);

        assertThat(result.get("refundedKrw")).isEqualTo(12_000L);
        verify(portOne).cancelPayment(eq("tmr-c2"), eq(10_000L), eq(10_000L), anyString());
        verify(portOne).cancelPayment(eq("tmr-c1"), eq(2_000L), eq(5_000L), anyString());
        assertThat(recent.getStatus()).isEqualTo(BillingPayment.Status.CANCELLED);
        assertThat(older.getStatus()).isEqualTo(BillingPayment.Status.PARTIAL_CANCELLED);
        assertThat(older.refundableKrw()).isEqualTo(3_000);
        verify(usageService).takeCredit(MEMBER, BigDecimal.valueOf(10_000));
        verify(usageService).takeCredit(MEMBER, BigDecimal.valueOf(2_000));
    }

    @Test
    void 결제_서비스에서_취소된_금액은_한_번만_반영한다() {
        BillingPayment p = creditPaid("tmr-c3", 10_000, Instant.now());

        service.applyRemoteCancellation(p, 4_000);
        service.applyRemoteCancellation(p, 4_000);

        assertThat(p.getRefundedKrw()).isEqualTo(4_000);
        verify(usageService, times(1)).takeCredit(MEMBER, BigDecimal.valueOf(4_000));
    }
}
