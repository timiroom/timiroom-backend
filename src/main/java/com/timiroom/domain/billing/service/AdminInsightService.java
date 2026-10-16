package com.timiroom.domain.billing.service;

import com.timiroom.domain.billing.repository.BillingPaymentRepository;
import com.timiroom.domain.usage.UsageProperties;
import com.timiroom.domain.usage.enums.PlanType;
import com.timiroom.domain.usage.service.UsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * 관리자 화면의 지표와 목록. 운영 화면에서 보는 숫자를 SQL 한 번씩으로 모은다.
 * 날짜는 모두 한국 시간 기준이다.
 */
@Service
@RequiredArgsConstructor
public class AdminInsightService {

    private static final int TREND_DAYS = 30;

    private final JdbcTemplate jdbc;
    private final UsageProperties properties;
    private final UsageService usageService;
    private final BillingService billingService;
    private final BillingPaymentRepository payments;

    /** 요약 화면의 추가 지표: 핵심 수치, 30일 추이, 기능·모델별 비용, 최근 목록 */
    @Transactional(readOnly = true)
    public Map<String, Object> insights() {
        ZoneId zone = ZoneId.of(properties.getZone());
        LocalDate today = LocalDate.now(zone);
        Timestamp monthStart = ts(today.withDayOfMonth(1), zone);
        Timestamp weekAgo = ts(today.minusDays(6), zone);
        Timestamp trendStart = ts(today.minusDays(TREND_DAYS - 1), zone);
        long price = properties.plan(PlanType.PRO).getPriceKrw();

        long members = count("select count(*) from member where deleted_at is null");
        long paying = count("select count(*) from member_subscription where plan = 'PRO' and granted_by_admin = false");
        long active7 = count("select count(distinct member_id) from ai_usage_event where created_at >= ?", weekAgo);
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("mrrKrw", paying * price);
        kpi.put("payingMembers", paying);
        kpi.put("conversionPercent", members == 0 ? 0 : BigDecimal.valueOf(paying * 100.0 / members).setScale(1, RoundingMode.HALF_UP));
        kpi.put("signupsToday", count("select count(*) from member where created_at >= current_date"));
        kpi.put("signups7d", count("select count(*) from member where created_at >= current_date - 6"));
        kpi.put("activeMembers7d", active7);
        kpi.put("aiCalls7d", count("select coalesce(sum(calls), 0) from ai_usage_event where created_at >= ?", weekAgo));
        kpi.put("byokShare7dPercent", percent(
                count("select coalesce(sum(calls), 0) from ai_usage_event where byok = true and created_at >= ?", weekAgo),
                count("select coalesce(sum(calls), 0) from ai_usage_event where created_at >= ?", weekAgo)));
        kpi.put("projects", count("select count(*) from project where deleted_at is null"));
        kpi.put("generations7d", count("select count(*) from pipeline_execution where created_at >= current_date - 6"));
        kpi.put("prReviews7d", count("select count(*) from github_pull_request_review_record where updated_at >= current_date - 6"));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kpi", kpi);
        view.put("trend", trend(trendStart, today, zone));
        view.put("features", jdbc.queryForList(
                "select feature, coalesce(sum(calls), 0) as calls, count(distinct member_id) as members, "
                        + "coalesce(sum(input_tokens + output_tokens), 0) as tokens, coalesce(sum(cost_krw), 0) as \"costKrw\" "
                        + "from ai_usage_event where created_at >= ? and byok = false group by feature order by sum(cost_krw) desc",
                monthStart));
        view.put("models", jdbc.queryForList(
                "select model, coalesce(sum(calls), 0) as calls, coalesce(sum(input_tokens), 0) as \"inputTokens\", "
                        + "coalesce(sum(output_tokens), 0) as \"outputTokens\", coalesce(sum(cost_krw), 0) as \"costKrw\" "
                        + "from ai_usage_event where created_at >= ? and byok = false group by model order by sum(cost_krw) desc limit 10",
                monthStart));
        view.put("recentPayments", jdbc.queryForList(
                "select p.billing_payment_id as id, p.kind, p.amount_krw as \"amountKrw\", p.refunded_krw as \"refundedKrw\", p.status, "
                        + "p.failure_reason as \"failureReason\", coalesce(p.paid_at, p.created_at) as at, m.email "
                        + "from billing_payment p left join member m on m.member_id = p.member_id order by p.created_at desc limit 8"));
        view.put("recentSignups", jdbc.queryForList(
                "select member_id as \"memberId\", email, coalesce(nickname, member_name) as name, provider, created_at as \"createdAt\" "
                        + "from member where deleted_at is null order by created_at desc limit 8"));
        view.put("upcomingRenewals", jdbc.queryForList(
                "select s.member_id as \"memberId\", m.email, s.next_billing_at as \"nextBillingAt\", s.status, s.failed_attempts as \"failedAttempts\" "
                        + "from member_subscription s left join member m on m.member_id = s.member_id "
                        + "where s.plan = 'PRO' and s.granted_by_admin = false and s.cancel_at_period_end = false "
                        + "and s.next_billing_at < ? order by s.next_billing_at limit 10",
                Timestamp.from(Instant.now().plusSeconds(7 * 86_400))));
        return view;
    }

    /** 최근 30일 날짜별 결제·환불·AI 비용·가입·AI 호출 */
    private List<Map<String, Object>> trend(Timestamp from, LocalDate today, ZoneId zone) {
        Map<LocalDate, Map<String, Object>> days = new LinkedHashMap<>();
        for (int i = TREND_DAYS - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            row.put("revenueKrw", 0L);
            row.put("refundKrw", 0L);
            row.put("aiCostKrw", 0L);
            row.put("aiCalls", 0L);
            row.put("signups", 0L);
            days.put(d, row);
        }
        String tz = properties.getZone();
        fill(days, "revenueKrw", "select (paid_at at time zone '" + tz + "')::date as d, sum(amount_krw) as v from billing_payment "
                + "where paid_at >= ? and status in ('PAID','PARTIAL_CANCELLED','CANCELLED') group by 1", from);
        fill(days, "refundKrw", "select (refunded_at at time zone '" + tz + "')::date as d, sum(refunded_krw) as v from billing_payment "
                + "where refunded_at >= ? group by 1", from);
        fill(days, "aiCostKrw", "select (created_at at time zone '" + tz + "')::date as d, sum(cost_krw) as v from ai_usage_event "
                + "where created_at >= ? and byok = false group by 1", from);
        fill(days, "aiCalls", "select (created_at at time zone '" + tz + "')::date as d, sum(calls) as v from ai_usage_event "
                + "where created_at >= ? group by 1", from);
        fill(days, "signups", "select created_at::date as d, count(*) as v from member where created_at >= ?::timestamp group by 1",
                Timestamp.valueOf(today.minusDays(TREND_DAYS - 1).atStartOfDay()));
        return new ArrayList<>(days.values());
    }

    private void fill(Map<LocalDate, Map<String, Object>> days, String key, String sql, Object arg) {
        jdbc.query(sql, rs -> {
            LocalDate d = rs.getDate("d").toLocalDate();
            Map<String, Object> row = days.get(d);
            if (row != null) row.put(key, rs.getBigDecimal("v") == null ? 0L : rs.getBigDecimal("v").setScale(0, RoundingMode.HALF_UP).longValue());
        }, arg);
    }

    /** 회원 한 명의 상세: 기본 정보, 요금제, 사용량, 기능별 비용, 최근 AI 호출, 결제 */
    @Transactional(readOnly = true)
    public Map<String, Object> memberDetail(Long memberId) {
        List<Map<String, Object>> found = jdbc.queryForList(
                "select member_id as \"memberId\", email, coalesce(nickname, member_name) as name, provider, role, "
                        + "github_login as \"githubLogin\", created_at as \"createdAt\" from member where member_id = ?", memberId);
        if (found.isEmpty()) throw new IllegalArgumentException("회원을 찾을 수 없습니다");
        UsageService.Status status = usageService.statusOf(memberId);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("plan", status.plan().name());
        usage.put("percent", status.percent());
        usage.put("usedKrw", status.usedKrw());
        usage.put("limitKrw", status.limitKrw());
        usage.put("periodStart", status.period().start());
        usage.put("periodEnd", status.period().end());
        usage.put("creditBalanceKrw", status.credit().balanceKrw().setScale(0, RoundingMode.DOWN));
        usage.put("creditEnabled", status.credit().enabled());
        usage.put("creditMonthlyCapKrw", status.credit().monthlyCapKrw());

        Timestamp periodStart = Timestamp.from(status.period().start());
        Map<String, Object> view = new LinkedHashMap<>(found.get(0));
        view.put("subscription", billingService.subscriptionView(memberId));
        view.put("usage", usage);
        view.put("ownKey", count("select count(*) from llm_credential where member_id = ?", memberId) > 0);
        view.put("projects", count("select count(*) from project_member where member_id = ?", memberId));
        view.put("features", jdbc.queryForList(
                "select feature, coalesce(sum(calls), 0) as calls, coalesce(sum(cost_krw), 0) as \"costKrw\" from ai_usage_event "
                        + "where member_id = ? and created_at >= ? group by feature order by sum(cost_krw) desc", memberId, periodStart));
        view.put("recentUsage", jdbc.queryForList(
                "select created_at as at, feature, model, input_tokens as \"inputTokens\", output_tokens as \"outputTokens\", "
                        + "cost_krw as \"costKrw\", credit_krw as \"creditKrw\", byok from ai_usage_event where member_id = ? "
                        + "order by created_at desc limit 15", memberId));
        view.put("payments", payments.findByMemberIdOrderByCreatedAtDesc(memberId, PageRequest.of(0, 10)).getContent()
                .stream().map(BillingService::paymentView).toList());
        return view;
    }

    /** AI 호출 기록(최근 것부터). feature가 있으면 그 기능만. */
    @Transactional(readOnly = true)
    public Map<String, Object> usageEvents(String feature, String query, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        String like = "%" + (query == null ? "" : query.trim().toLowerCase(Locale.ROOT)) + "%";
        boolean byFeature = feature != null && !feature.isBlank();
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "select e.usage_event_id as id, e.created_at as at, e.member_id as \"memberId\", m.email, e.project_id as \"projectId\", "
                        + "e.feature, e.provider, e.model, e.input_tokens as \"inputTokens\", e.output_tokens as \"outputTokens\", e.calls, "
                        + "e.cost_krw as \"costKrw\", e.credit_krw as \"creditKrw\", e.byok, e.counted "
                        + "from ai_usage_event e left join member m on m.member_id = e.member_id where lower(coalesce(m.email, '')) like ?");
        args.add(like);
        if (byFeature) {
            sql.append(" and e.feature = ?");
            args.add(feature.toUpperCase(Locale.ROOT));
        }
        sql.append(" order by e.created_at desc limit ? offset ?");
        args.add(limit + 1);
        args.add(offset);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        boolean hasNext = rows.size() > limit;
        return Map.of("content", rows.subList(0, Math.min(rows.size(), limit)), "page", Math.max(page, 0), "hasNext", hasNext);
    }

    private long count(String sql, Object... args) {
        Number value = jdbc.queryForObject(sql, Number.class, args);
        return value == null ? 0 : value.longValue();
    }

    private static BigDecimal percent(long part, long total) {
        return total == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(part * 100.0 / total).setScale(1, RoundingMode.HALF_UP);
    }

    private static Timestamp ts(LocalDate day, ZoneId zone) {
        return Timestamp.from(day.atStartOfDay(zone).toInstant());
    }
}
