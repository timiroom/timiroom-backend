package com.timiroom.domain.usage.service;

import com.timiroom.domain.usage.UsageProperties;
import com.timiroom.domain.usage.entity.MemberSubscription;
import com.timiroom.domain.usage.entity.UsageEvent;
import com.timiroom.domain.usage.enums.PlanType;
import com.timiroom.domain.usage.enums.UsageFeature;
import com.timiroom.domain.usage.repository.MemberSubscriptionRepository;
import com.timiroom.domain.usage.repository.UsageEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/**
 * 사용량 한도 확인과 기록.
 * AI 호출은 끝나야 사용량을 알 수 있어 "쓰기 전에 남은 양 확인 → 쓴 뒤 기록" 순서로 쓴다.
 * 그래서 마지막 호출 하나는 한도를 조금 넘을 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageService {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final UsageProperties properties;
    private final UsageEventRepository usageEvents;
    private final MemberSubscriptionRepository subscriptions;

    public record Period(Instant start, Instant end) {}

    public record Status(PlanType plan, String planName, long limitKrw, BigDecimal usedKrw,
                         BigDecimal remainingKrw, int percent, Period period) {}

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
        BigDecimal used = usageEvents.sumCountedCost(memberId, period.start(), period.end());
        BigDecimal limit = BigDecimal.valueOf(planConfig.getMonthlyLimitKrw());
        BigDecimal remaining = limit.subtract(used).max(BigDecimal.ZERO);
        int percent = limit.signum() == 0 ? 100
                : used.multiply(BigDecimal.valueOf(100)).divide(limit, 0, RoundingMode.DOWN).min(BigDecimal.valueOf(100)).intValue();
        return new Status(plan, planConfig.getName(), planConfig.getMonthlyLimitKrw(),
                used.setScale(0, RoundingMode.HALF_UP), remaining.setScale(0, RoundingMode.DOWN), percent, period);
    }

    /**
     * AI 기능을 시작해도 되는지 확인한다. 사용자 키로 부르는 호출은 우리 비용이 거의 없어 막지 않는다.
     * @throws UsageLimitExceededException 남은 금액이 기능별 최소 금액보다 적을 때
     */
    @Transactional(readOnly = true)
    public void requireAllowance(Long memberId, UsageFeature feature, boolean usingOwnKey) {
        if (!properties.isEnforce() || usingOwnKey || memberId == null) return;
        Status status = statusOf(memberId);
        BigDecimal reserve = properties.getReserveKrw().getOrDefault(feature, BigDecimal.ZERO);
        BigDecimal remaining = BigDecimal.valueOf(status.limitKrw()).subtract(status.usedKrw());
        if (remaining.compareTo(reserve) <= 0) {
            throw new UsageLimitExceededException(status.period().end(), status.plan().name());
        }
    }

    /** 사용량을 기록한다. 사용자 키 호출은 비용 0으로 남긴다. 기록 실패가 기능 자체를 막지 않도록 예외를 삼킨다. */
    @Transactional
    public void record(Long memberId, Long projectId, UsageFeature feature, List<AiUsageRow> rows,
                       String reference, boolean counted) {
        if (memberId == null || rows == null || rows.isEmpty()) return;
        try {
            Instant now = Instant.now();
            for (AiUsageRow row : rows) {
                usageEvents.save(UsageEvent.builder()
                        .memberId(memberId).projectId(projectId).feature(feature)
                        .kind(truncate(row.kind(), 20)).provider(truncate(row.provider(), 40))
                        .model(truncate(row.model(), 120))
                        .inputTokens(row.inputTokens()).outputTokens(row.outputTokens()).calls(row.calls())
                        .costKrw(row.byok() ? BigDecimal.ZERO : costOf(row))
                        .byok(row.byok()).counted(counted && !row.byok())
                        .reference(truncate(reference, 80)).createdAt(now)
                        .build());
            }
        } catch (RuntimeException e) {
            log.error("사용량 기록 실패 | memberId: {}, feature: {}", memberId, feature, e);
        }
    }

    public BigDecimal costOf(AiUsageRow row) {
        UsageProperties.ModelPrice price = properties.priceOf(row.model());
        return price.getInputKrwPerMillion().multiply(BigDecimal.valueOf(row.inputTokens()))
                .add(price.getOutputKrwPerMillion().multiply(BigDecimal.valueOf(row.outputTokens())))
                .divide(MILLION, 4, RoundingMode.HALF_UP);
    }

    /** 사용량 화면용: 기능별·날짜별 합계와 사용자 키 사용분. */
    @Transactional(readOnly = true)
    public Map<String, Object> breakdown(Long memberId) {
        Instant now = Instant.now();
        Period period = periodOf(subscriptionOf(memberId), now);
        ZoneId zone = ZoneId.of(properties.getZone());
        List<UsageEvent> events = usageEvents
                .findByMemberIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(memberId, period.start(), period.end());

        Map<UsageFeature, Map<String, Object>> byFeature = new EnumMap<>(UsageFeature.class);
        TreeMap<LocalDate, BigDecimal> daily = new TreeMap<>();
        long ownKeyInput = 0, ownKeyOutput = 0, totalInput = 0, totalOutput = 0;
        for (UsageEvent e : events) {
            Map<String, Object> row = byFeature.computeIfAbsent(e.getFeature(), f -> new LinkedHashMap<>(Map.of(
                    "feature", f.name(), "costKrw", BigDecimal.ZERO, "inputTokens", 0L, "outputTokens", 0L, "calls", 0)));
            if (e.isCounted()) row.put("costKrw", ((BigDecimal) row.get("costKrw")).add(e.getCostKrw()));
            row.put("inputTokens", (Long) row.get("inputTokens") + e.getInputTokens());
            row.put("outputTokens", (Long) row.get("outputTokens") + e.getOutputTokens());
            row.put("calls", (Integer) row.get("calls") + e.getCalls());
            if (e.isCounted()) daily.merge(e.getCreatedAt().atZone(zone).toLocalDate(), e.getCostKrw(), BigDecimal::add);
            totalInput += e.getInputTokens();
            totalOutput += e.getOutputTokens();
            if (e.isByok()) { ownKeyInput += e.getInputTokens(); ownKeyOutput += e.getOutputTokens(); }
        }
        byFeature.values().forEach(row -> row.put("costKrw", ((BigDecimal) row.get("costKrw")).setScale(0, RoundingMode.HALF_UP)));

        List<Map<String, Object>> dailyRows = new ArrayList<>();
        LocalDate day = period.start().atZone(zone).toLocalDate();
        LocalDate last = LocalDate.ofInstant(now.isBefore(period.end()) ? now : period.end().minusSeconds(1), zone);
        for (; !day.isAfter(last); day = day.plusDays(1)) {
            dailyRows.add(Map.of("date", day.toString(),
                    "costKrw", daily.getOrDefault(day, BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP)));
        }
        return Map.of(
                "features", new ArrayList<>(byFeature.values()),
                "daily", dailyRows,
                "tokens", Map.of("input", totalInput, "output", totalOutput,
                        "ownKeyInput", ownKeyInput, "ownKeyOutput", ownKeyOutput));
    }

    @Transactional(readOnly = true)
    public Page<UsageEvent> recent(Long memberId, int page, int size) {
        return usageEvents.findByMemberIdOrderByCreatedAtDesc(memberId, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)));
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
