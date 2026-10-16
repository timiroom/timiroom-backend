package com.timiroom.domain.usage;

import com.timiroom.domain.usage.entity.MemberSubscription;
import com.timiroom.domain.usage.entity.UsageEvent;
import com.timiroom.domain.usage.enums.PlanType;
import com.timiroom.domain.usage.enums.UsageFeature;
import com.timiroom.domain.usage.repository.MemberCreditRepository;
import com.timiroom.domain.usage.repository.MemberSubscriptionRepository;
import com.timiroom.domain.usage.entity.MemberCredit;
import com.timiroom.domain.usage.repository.UsageEventRepository;
import com.timiroom.domain.usage.service.AiUsageRow;
import com.timiroom.domain.usage.service.UsageLimitExceededException;
import com.timiroom.domain.usage.service.UsageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UsageServiceTest {

    private UsageProperties properties;
    private UsageEventRepository events;
    private MemberSubscriptionRepository subscriptions;
    private MemberCreditRepository credits;
    private UsageService service;

    @BeforeEach
    void setUp() {
        properties = new UsageProperties();
        properties.getModels().add(new UsageProperties.ModelPrice("gpt-5.4-mini*", new BigDecimal("1000"), new BigDecimal("4000")));
        events = mock(UsageEventRepository.class);
        subscriptions = mock(MemberSubscriptionRepository.class);
        when(subscriptions.findById(any())).thenReturn(Optional.empty());
        credits = mock(MemberCreditRepository.class);
        when(credits.findById(any())).thenReturn(Optional.empty());
        when(credits.findForUpdate(any())).thenReturn(Optional.empty());
        service = new UsageService(properties, events, subscriptions, credits);
    }

    @Test
    void 비용은_모델_단가로_원_단위_계산한다() {
        var row = new AiUsageRow("chat", "server", "gpt-5.4-mini-2026", false, 1_000_000, 500_000, 3);
        assertThat(service.costOf(row)).isEqualByComparingTo("3000");
        var unknown = new AiUsageRow("chat", "server", "other", false, 1_000_000, 0, 1);
        assertThat(service.costOf(unknown)).isEqualByComparingTo("1500");
    }

    @Test
    void 무료는_달력_월_단위로_세고_서울_시간으로_초기화한다() {
        Instant now = ZonedDateTime.of(2026, 10, 31, 23, 30, 0, 0, ZoneId.of("Asia/Seoul")).toInstant();
        var period = service.periodOf(MemberSubscription.free(1L), now);
        assertThat(period.start()).isEqualTo(ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant());
        assertThat(period.end()).isEqualTo(ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneId.of("Asia/Seoul")).toInstant());
    }

    @Test
    void 프로는_결제한_기간_단위로_센다() {
        var pro = MemberSubscription.free(1L);
        Instant start = Instant.now().minus(Duration.ofDays(3));
        pro.startProPeriod(start, start.plus(Duration.ofDays(30)));
        var period = service.periodOf(pro, Instant.now());
        assertThat(period.start()).isEqualTo(start);
        when(subscriptions.findById(1L)).thenReturn(Optional.of(pro));
        when(events.sumCountedCost(eq(1L), any(), any())).thenReturn(new BigDecimal("15000"));
        var status = service.statusOf(1L);
        assertThat(status.plan()).isEqualTo(PlanType.PRO);
        assertThat(status.limitKrw()).isEqualTo(30_000);
        assertThat(status.percent()).isEqualTo(50);
    }

    @Test
    void 한도를_다_쓰면_막고_사용자_키면_통과한다() {
        when(events.sumCountedCost(eq(1L), any(), any())).thenReturn(new BigDecimal("3000"));
        assertThatThrownBy(() -> service.requireAllowance(1L, UsageFeature.AI_CHAT, false))
                .isInstanceOf(UsageLimitExceededException.class);
        service.requireAllowance(1L, UsageFeature.AI_CHAT, true);
    }

    @Test
    void 프로젝트_생성은_최소_남은_금액이_있어야_시작한다() {
        when(events.sumCountedCost(eq(1L), any(), any())).thenReturn(new BigDecimal("2800"));
        service.requireAllowance(1L, UsageFeature.AI_CHAT, false);
        assertThatThrownBy(() -> service.requireAllowance(1L, UsageFeature.PROJECT_GENERATION, false))
                .isInstanceOf(UsageLimitExceededException.class);
    }

    @Test
    void 한도_검사를_끄면_막지_않는다() {
        properties.setEnforce(false);
        service.requireAllowance(1L, UsageFeature.AI_CHAT, false);
        verifyNoInteractions(events);
    }

    @Test
    void 사용자_키_사용분은_비용_0이고_한도에_넣지_않는다() {
        service.record(1L, 5L, UsageFeature.AI_CHAT, List.of(
                new AiUsageRow("chat", "openai", "gpt-user", true, 100, 50, 1),
                new AiUsageRow("embedding", "server", "solar-embedding-2", false, 1000, 0, 1)), "ref", true);
        ArgumentCaptor<UsageEvent> saved = ArgumentCaptor.forClass(UsageEvent.class);
        verify(events, times(2)).save(saved.capture());
        UsageEvent own = saved.getAllValues().get(0);
        assertThat(own.isByok()).isTrue();
        assertThat(own.isCounted()).isFalse();
        assertThat(own.getCostKrw()).isEqualByComparingTo("0");
        assertThat(saved.getAllValues().get(1).isCounted()).isTrue();
        assertThat(saved.getAllValues().get(1).getProjectId()).isEqualTo(5L);
    }

    @Test
    void 기록_실패는_기능을_막지_않는다() {
        when(events.save(any())).thenThrow(new RuntimeException("db down"));
        service.record(1L, null, UsageFeature.AI_CHAT, List.of(new AiUsageRow("chat", "server", "m", false, 1, 1, 1)), null, true);
    }

    @Test
    void 요금제를_다_쓰면_추가_사용량으로_계속_쓴다() {
        when(events.sumCountedCost(eq(1L), any(), any())).thenReturn(new BigDecimal("3000"));
        MemberCredit credit = MemberCredit.empty(1L);
        credit.add(new BigDecimal("1500"));
        when(credits.findById(1L)).thenReturn(Optional.of(credit));
        service.requireAllowance(1L, UsageFeature.AI_CHAT, false);
        assertThat(service.statusOf(1L).creditKrw()).isEqualByComparingTo("1500");
    }

    @Test
    void 한도를_넘는_몫만_추가_사용량에서_차감한다() {
        when(events.sumCountedCost(eq(1L), any(), any())).thenReturn(new BigDecimal("2900"));
        MemberCredit credit = MemberCredit.empty(1L);
        credit.add(new BigDecimal("1000"));
        when(credits.findForUpdate(1L)).thenReturn(Optional.of(credit));

        // 1,000,000 입력 토큰 x 1000원 = 1000원 → 100원은 요금제, 900원은 추가 사용량
        service.record(1L, null, UsageFeature.AI_CHAT,
                List.of(new AiUsageRow("chat", "server", "gpt-5.4-mini", false, 1_000_000, 0, 1)), null, true);

        ArgumentCaptor<UsageEvent> saved = ArgumentCaptor.forClass(UsageEvent.class);
        verify(events).save(saved.capture());
        assertThat(saved.getValue().getCreditKrw()).isEqualByComparingTo("900");
        assertThat(credit.getBalanceKrw()).isEqualByComparingTo("100");
    }

    @Test
    void 차감_비율을_정하면_비용에_비율을_곱해_잔액에서_뺀다() {
        properties.setCreditRate(new BigDecimal("1.5"));
        when(events.sumCountedCost(eq(1L), any(), any())).thenReturn(new BigDecimal("3000"));
        MemberCredit credit = MemberCredit.empty(1L);
        credit.add(new BigDecimal("3000"));
        when(credits.findForUpdate(1L)).thenReturn(Optional.of(credit));

        // 비용 1000원 → 잔액에서 1500원 차감
        service.record(1L, null, UsageFeature.AI_CHAT,
                List.of(new AiUsageRow("chat", "server", "gpt-5.4-mini", false, 1_000_000, 0, 1)), null, true);

        assertThat(credit.getBalanceKrw()).isEqualByComparingTo("1500");
    }
}
