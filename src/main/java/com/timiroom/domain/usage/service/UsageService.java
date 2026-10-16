package com.timiroom.domain.usage.service;

import com.timiroom.domain.usage.UsageProperties;
import com.timiroom.domain.usage.entity.MemberCredit;
import com.timiroom.domain.usage.entity.MemberSubscription;
import com.timiroom.domain.usage.entity.UsageEvent;
import com.timiroom.domain.usage.enums.PlanType;
import com.timiroom.domain.usage.enums.UsageFeature;
import com.timiroom.domain.usage.repository.MemberCreditRepository;
import com.timiroom.domain.usage.repository.MemberSubscriptionRepository;
import com.timiroom.domain.usage.repository.UsageEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/**
 * 사용량 한도 확인과 기록.
 * AI 호출은 끝나야 사용량을 알 수 있어 "쓰기 전에 남은 양 확인 → 쓴 뒤 기록" 순서로 쓴다.
 * 요금제 한도를 먼저 쓰고, 다 쓰면 추가 사용량(크레딧)에서 차감한다.
 * 금액은 내부 계산에만 쓰고, 사용자에게는 요금제 한도 대비 비율로만 보여 준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageService {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final UsageProperties properties;
    private final UsageEventRepository usageEvents;
    private final MemberSubscriptionRepository subscriptions;
    private final MemberCreditRepository credits;

    public record Period(Instant start, Instant end) {}

    public record Status(PlanType plan, String planName, long limitKrw, BigDecimal usedKrw,
                         BigDecimal remainingKrw, int percent, BigDecimal creditKrw, Period period,
                         Credit credit) {}

    /** 추가 사용량 설정과 이번 기간 쓴 금액(원). usableKrw는 지금 더 쓸 수 있는 잔액(꺼짐·상한 반영). */
    public record Credit(boolean enabled, Long monthlyCapKrw, BigDecimal balanceKrw, BigDecimal spentKrw, BigDecimal usableKrw) {}

    @Transactional(readOnly = true)
    public MemberSubscription subscriptionOf(Long memberId) {
        return subscriptions.findById(memberId).orElseGet(() -> MemberSubscription.free(memberId));
    }

    /** 프로는 결제한 기간, 무료(또는 기간이 끝난 프로)는 달력 기준 이번 달. */
    public Period periodOf(MemberSubscription subscription, Instant now) {
        if (subscription.isPro() && subscription.getPeriodStart() != null && subscription.getPeriodEnd() != null
                && !now.isBefore(subscription.getPeriodStart()) && now.isBefore(subscription.getPeriodEnd())) {
            return new Period(subscription.getPeriodStart(), subscription.getPeriodEnd());
        }
        ZoneId zone = ZoneId.of(properties.getZone());
        LocalDate first = now.atZone(zone).toLocalDate().withDayOfMonth(1);
        return new Period(first.atStartOfDay(zone).toInstant(), first.plusMonths(1).atStartOfDay(zone).toInstant());
    }

    @Transactional(readOnly = true)
    public Status statusOf(Long memberId) {
        Instant now = Instant.now();
        MemberSubscription subscription = subscriptionOf(memberId);
        PlanType plan = effectivePlan(subscription, now);
        Period period = periodOf(subscription, now);
        UsageProperties.Plan planConfig = properties.plan(plan);
        BigDecimal used = Objects.requireNonNullElse(usageEvents.sumCountedCost(memberId, period.start(), period.end()), BigDecimal.ZERO);
        BigDecimal limit = BigDecimal.valueOf(planConfig.getMonthlyLimitKrw());
        BigDecimal remaining = limit.subtract(used).max(BigDecimal.ZERO);
        Credit credit = creditOf(memberId, period);
        return new Status(plan, planConfig.getName(), planConfig.getMonthlyLimitKrw(),
                used.setScale(0, RoundingMode.HALF_UP), remaining.setScale(0, RoundingMode.DOWN),
                percentOf(used, limit, true), credit.balanceKrw(), period, credit);
    }

    /** 추가 사용량 상태. 꺼져 있거나 이번 달 상한을 다 썼으면 usableKrw는 0이다. */
    Credit creditOf(Long memberId, Period period) {
        MemberCredit row = credits.findById(memberId).orElse(null);
        BigDecimal balance = row != null ? row.getBalanceKrw() : BigDecimal.ZERO;
        boolean enabled = row != null && row.isEnabled();
        Long cap = row != null ? row.getMonthlyCapKrw() : null;
        BigDecimal spent = Objects.requireNonNullElse(usageEvents.sumCreditCovered(memberId, period.start(), period.end()),
                BigDecimal.ZERO).multiply(rate());
        BigDecimal usable = enabled ? balance : BigDecimal.ZERO;
        if (enabled && cap != null) usable = usable.min(BigDecimal.valueOf(cap).subtract(spent).max(BigDecimal.ZERO));
        return new Credit(enabled, cap, balance, spent.setScale(0, RoundingMode.HALF_UP), usable);
    }

    /** limit 대비 비율(%). capped면 100을 넘기지 않는다. */
    static int percentOf(BigDecimal value, BigDecimal limit, boolean capped) {
        if (limit.signum() == 0) return value.signum() > 0 ? 100 : 0;
        BigDecimal percent = value.multiply(HUNDRED).divide(limit, 0, capped ? RoundingMode.DOWN : RoundingMode.HALF_UP);
        return capped ? percent.min(HUNDRED).intValue() : percent.intValue();
    }

    /**
     * AI 기능을 시작해도 되는지 확인한다. 사용자 키로 부르는 호출은 우리 비용이 거의 없어 막지 않는다.
     * @throws UsageLimitExceededException 요금제 남은 양 + 추가 사용량이 기능별 최소 금액보다 적을 때
     */
    @Transactional(readOnly = true)
    public void requireAllowance(Long memberId, UsageFeature feature, boolean usingOwnKey) {
        if (!properties.isEnforce() || usingOwnKey || memberId == null) return;
        Status status = statusOf(memberId);
        BigDecimal reserve = properties.getReserveKrw().getOrDefault(feature, BigDecimal.ZERO);
        // 추가 사용량 잔액(원)을 내부 비용 단위로 바꿔 더한다.
        BigDecimal available = BigDecimal.valueOf(status.limitKrw()).subtract(status.usedKrw()).max(BigDecimal.ZERO)
                .add(status.credit().usableKrw().divide(rate(), 4, RoundingMode.DOWN));
        if (available.compareTo(reserve) <= 0) {
            throw new UsageLimitExceededException(status.period().end(), status.plan().name());
        }
    }

    /**
     * 사용량을 기록한다. 요금제 한도를 넘는 몫은 추가 사용량에서 차감한다.
     * 사용자 키 호출은 비용 0으로 남긴다. 기록 실패가 기능 자체를 막지 않도록 예외를 삼킨다.
     */
    @Transactional
    public void record(Long memberId, Long projectId, UsageFeature feature, List<AiUsageRow> rows,
                       String reference, boolean counted) {
        if (memberId == null || rows == null || rows.isEmpty()) return;
        try {
            Instant now = Instant.now();
            BigDecimal planLeft = null;
            MemberCredit credit = null;
            BigDecimal creditRoom = null; // 이번 기록에서 추가 사용량으로 더 뺄 수 있는 금액(원)
            for (AiUsageRow row : rows) {
                BigDecimal cost = row.byok() ? BigDecimal.ZERO : costOf(row);
                boolean countThis = counted && !row.byok();
                BigDecimal fromCredit = BigDecimal.ZERO;
                if (countThis && cost.signum() > 0) {
                    if (planLeft == null) planLeft = planRemaining(memberId, now);
                    BigDecimal over = cost.subtract(planLeft.max(BigDecimal.ZERO));
                    planLeft = planLeft.subtract(cost);
                    if (over.signum() > 0) {
                        if (credit == null) {
                            credit = credits.findForUpdate(memberId).orElse(null);
                            creditRoom = credit == null ? BigDecimal.ZERO
                                    : creditOf(memberId, periodOf(subscriptionOf(memberId), now)).usableKrw();
                        }
                        // 넘친 비용 x 비율만큼 잔액(원)에서 빼고, 기록에는 그만큼 덮은 비용을 남긴다.
                        // 꺼져 있거나 월 상한을 넘는 몫은 빼지 않는다(그 호출은 한도를 조금 넘긴 채 끝난다).
                        if (credit != null && creditRoom.signum() > 0) {
                            BigDecimal taken = credit.take(over.multiply(rate()).min(creditRoom));
                            creditRoom = creditRoom.subtract(taken);
                            fromCredit = taken.divide(rate(), 4, RoundingMode.HALF_UP);
                        }
                    }
                }
                usageEvents.save(UsageEvent.builder()
                        .memberId(memberId).projectId(projectId).feature(feature)
                        .kind(truncate(row.kind(), 20)).provider(truncate(row.provider(), 40))
                        .model(truncate(row.model(), 120))
                        .inputTokens(row.inputTokens()).outputTokens(row.outputTokens()).calls(row.calls())
                        .costKrw(cost).creditKrw(fromCredit)
                        .byok(row.byok()).counted(countThis)
                        .reference(truncate(reference, 80)).createdAt(now)
                        .build());
            }
        } catch (RuntimeException e) {
            log.error("사용량 기록 실패 | memberId: {}, feature: {}", memberId, feature, e);
        }
    }

    private BigDecimal planRemaining(Long memberId, Instant now) {
        MemberSubscription subscription = subscriptionOf(memberId);
        Period period = periodOf(subscription, now);
        BigDecimal limit = BigDecimal.valueOf(properties.plan(effectivePlan(subscription, now)).getMonthlyLimitKrw());
        return limit.subtract(Objects.requireNonNullElse(usageEvents.sumCountedCost(memberId, period.start(), period.end()), BigDecimal.ZERO));
    }

    /** 추가 사용량 충전(결제 성공 후). 충전하면 사용을 켠다. */
    @Transactional
    public void addCredit(Long memberId, BigDecimal amount) {
        MemberCredit credit = credits.findForUpdate(memberId).orElseGet(() -> MemberCredit.empty(memberId));
        credit.add(amount);
        if (!credit.isEnabled()) credit.configure(true, credit.getMonthlyCapKrw());
        credits.save(credit);
    }

    /** 추가 사용량 켜기·끄기와 월 상한(null이면 제한 없음) */
    @Transactional
    public void configureCredit(Long memberId, boolean enabled, Long monthlyCapKrw) {
        if (monthlyCapKrw != null && (monthlyCapKrw < 1_000 || monthlyCapKrw > 10_000_000)) {
            throw new IllegalArgumentException("월 상한은 1,000원 이상으로 정해 주세요");
        }
        MemberCredit credit = credits.findForUpdate(memberId).orElseGet(() -> MemberCredit.empty(memberId));
        credit.configure(enabled, monthlyCapKrw);
        credits.save(credit);
    }

    private BigDecimal rate() {
        BigDecimal rate = properties.getCreditRate();
        return rate == null || rate.signum() <= 0 ? BigDecimal.ONE : rate;
    }

    public BigDecimal costOf(AiUsageRow row) {
        UsageProperties.ModelPrice price = properties.priceOf(row.model());
        return price.getInputKrwPerMillion().multiply(BigDecimal.valueOf(row.inputTokens()))
                .add(price.getOutputKrwPerMillion().multiply(BigDecimal.valueOf(row.outputTokens())))
                .divide(MILLION, 4, RoundingMode.HALF_UP);
    }

    /** 이번 기간 기능별 비중(%). 금액은 내보내지 않는다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> featureShares(Long memberId) {
        Period period = periodOf(subscriptionOf(memberId), Instant.now());
        List<Object[]> rows = usageEvents.sumCostByFeature(memberId, period.start(), period.end());
        BigDecimal total = rows.stream().map(r -> (BigDecimal) r[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) return List.of();
        List<Map<String, Object>> shares = new ArrayList<>();
        for (Object[] r : rows) {
            int percent = ((BigDecimal) r[1]).multiply(HUNDRED).divide(total, 0, RoundingMode.HALF_UP).intValue();
            shares.add(Map.of("feature", ((UsageFeature) r[0]).name(), "percent", Math.max(percent, 1)));
        }
        shares.sort((a, b) -> Integer.compare((Integer) b.get("percent"), (Integer) a.get("percent")));
        return shares;
    }

    /** 기간이 끝났는데 아직 정리되지 않은 프로는 무료로 본다(정리는 결제 스케줄러가 한다). */
    public PlanType effectivePlan(MemberSubscription subscription, Instant now) {
        if (!subscription.isPro()) return PlanType.FREE;
        if (subscription.getPeriodEnd() != null && !now.isBefore(subscription.getPeriodEnd())
                && subscription.getStatus() != MemberSubscription.Status.PAST_DUE) {
            return subscription.isCancelAtPeriodEnd() ? PlanType.FREE : PlanType.PRO;
        }
        return PlanType.PRO;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
