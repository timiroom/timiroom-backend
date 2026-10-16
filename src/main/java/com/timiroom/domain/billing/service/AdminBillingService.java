package com.timiroom.domain.billing.service;

import com.timiroom.domain.billing.entity.BillingPayment;
import com.timiroom.domain.billing.repository.BillingPaymentRepository;
import com.timiroom.domain.usage.UsageProperties;
import com.timiroom.domain.usage.enums.PlanType;
import com.timiroom.domain.usage.service.UsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** 관리자 화면: 매출·비용 요약, 회원별 요금제·사용량, 결제 목록. 금액은 원 단위 그대로 보여 준다. */
@Service
@RequiredArgsConstructor
public class AdminBillingService {

    private final JdbcTemplate jdbc;
    private final BillingPaymentRepository payments;
    private final UsageService usageService;
    private final UsageProperties properties;

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        ZoneId zone = ZoneId.of(properties.getZone());
        LocalDate today = LocalDate.now(zone);
        Timestamp dayStart = Timestamp.from(today.atStartOfDay(zone).toInstant());
        Timestamp monthStart = Timestamp.from(today.withDayOfMonth(1).atStartOfDay(zone).toInstant());
        Timestamp now = Timestamp.from(Instant.now());

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("proPaying", count("select count(*) from member_subscription where plan = 'PRO' and granted_by_admin = false"));
        view.put("proGranted", count("select count(*) from member_subscription where plan = 'PRO' and granted_by_admin = true"));
        view.put("pastDue", count("select count(*) from member_subscription where status = 'PAST_DUE'"));
        view.put("cancelScheduled", count("select count(*) from member_subscription where plan = 'PRO' and cancel_at_period_end = true and granted_by_admin = false"));
        view.put("members", count("select count(*) from member"));
        view.put("revenueMonthKrw", sum("select coalesce(sum(amount_krw), 0) from billing_payment where status in ('PAID','PARTIAL_CANCELLED','CANCELLED') and paid_at >= ?", monthStart));
        view.put("refundMonthKrw", sum("select coalesce(sum(refunded_krw), 0) from billing_payment where refunded_at >= ?", monthStart));
        view.put("aiCostTodayKrw", sum("select coalesce(sum(cost_krw), 0) from ai_usage_event where byok = false and created_at >= ?", dayStart));
        view.put("aiCostMonthKrw", sum("select coalesce(sum(cost_krw), 0) from ai_usage_event where byok = false and created_at >= ?", monthStart));
        view.put("creditOutstandingKrw", sum("select coalesce(sum(balance_krw), 0) from member_credit"));
        view.put("failedPaymentsMonth", count("select count(*) from billing_payment where status = 'FAILED' and created_at >= ?", monthStart));
        view.put("alertDailyTotalKrw", properties.getAlertDailyTotalKrw());
        view.put("plans", Map.of(
                "FREE", Map.of("limitKrw", properties.plan(PlanType.FREE).getMonthlyLimitKrw()),
                "PRO", Map.of("limitKrw", properties.plan(PlanType.PRO).getMonthlyLimitKrw(), "priceKrw", properties.plan(PlanType.PRO).getPriceKrw())));
        view.put("creditRate", properties.getCreditRate());
        view.put("topCostToday", jdbc.queryForList(
                "select e.member_id as \"memberId\", m.email as email, coalesce(m.nickname, m.member_name) as name, sum(e.cost_krw) as \"costKrw\" "
                        + "from ai_usage_event e left join member m on m.member_id = e.member_id "
                        + "where e.byok = false and e.created_at >= ? and e.created_at < ? "
                        + "group by e.member_id, m.email, m.nickname, m.member_name order by sum(e.cost_krw) desc limit 10",
                dayStart, now));
        return view;
    }

    /** 회원 목록(이메일·이름 검색). 사용량은 이번 기간 기준이다. */
    @Transactional(readOnly = true)
    public Map<String, Object> members(String query, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 50);
        int offset = Math.max(page, 0) * limit;
        String like = "%" + (query == null ? "" : query.trim().toLowerCase(Locale.ROOT)) + "%";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select m.member_id as \"memberId\", m.email as email, coalesce(m.nickname, m.member_name) as name, m.role as role, "
                        + "m.provider as provider, m.created_at as \"createdAt\", "
                        + "(select max(e.created_at) from ai_usage_event e where e.member_id = m.member_id) as \"lastActiveAt\" "
                        + "from member m where m.deleted_at is null and (lower(m.email) like ? or lower(coalesce(m.nickname, '')) like ?) "
                        + "order by m.member_id desc limit ? offset ?", like, like, limit + 1, offset);
        boolean hasNext = rows.size() > limit;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(0, Math.min(rows.size(), limit))) {
            Long memberId = ((Number) row.get("memberId")).longValue();
            UsageService.Status status = usageService.statusOf(memberId);
            Map<String, Object> item = new LinkedHashMap<>(row);
            item.put("plan", status.plan().name());
            item.put("percent", status.percent());
            item.put("usedKrw", status.usedKrw());
            item.put("limitKrw", status.limitKrw());
            item.put("creditBalanceKrw", status.credit().balanceKrw().longValue());
            item.put("periodEnd", status.period().end());
            items.add(item);
        }
        return Map.of("content", items, "page", Math.max(page, 0), "hasNext", hasNext);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> payments(String status, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
        Page<BillingPayment> result;
        if (status == null || status.isBlank()) {
            result = payments.findAllByOrderByCreatedAtDesc(pageable);
        } else {
            result = payments.findByStatusOrderByCreatedAtDesc(BillingPayment.Status.valueOf(status.toUpperCase(Locale.ROOT)), pageable);
        }
        Map<Long, String> emails = new HashMap<>();
        List<Map<String, Object>> content = result.getContent().stream().map(p -> {
            Map<String, Object> view = BillingService.paymentView(p);
            view.put("memberId", p.getMemberId());
            view.put("email", emails.computeIfAbsent(p.getMemberId(), id -> jdbc.query(
                    "select email from member where member_id = ?", rs -> rs.next() ? rs.getString(1) : null, id)));
            view.put("refundableKrw", p.refundableKrw());
            return view;
        }).toList();
        return Map.of("content", content, "page", result.getNumber(), "totalPages", result.getTotalPages());
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private long sum(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? 0 : value.longValue();
    }
}
