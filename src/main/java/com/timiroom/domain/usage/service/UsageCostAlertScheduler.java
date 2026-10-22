package com.timiroom.domain.usage.service;

import com.timiroom.domain.billing.service.BillingNotifier;
import com.timiroom.domain.usage.UsageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 운영 비용 경보. 매시간 오늘(한국 시간) 우리 키로 쓴 AI 비용을 보고,
 * 전체 또는 한 사람이 기준을 넘으면 운영자에게 하루 한 번 알린다(같은 날 같은 경보는 다시 보내지 않는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsageCostAlertScheduler {

    private final JdbcTemplate jdbc;
    private final UsageProperties properties;
    private final BillingNotifier notifier;

    @Scheduled(cron = "${usage.alert-cron:0 5 * * * *}", zone = "Asia/Seoul")
    public void run() {
        try {
            check(LocalDate.now(ZoneId.of(properties.getZone())));
        } catch (RuntimeException e) {
            log.warn("비용 경보 확인 실패: {}", e.getMessage());
        }
    }

    void check(LocalDate day) {
        ZoneId zone = ZoneId.of(properties.getZone());
        Timestamp from = Timestamp.from(day.atStartOfDay(zone).toInstant());
        Timestamp to = Timestamp.from(day.plusDays(1).atStartOfDay(zone).toInstant());
        BigDecimal total = Objects.requireNonNullElse(jdbc.queryForObject(
                "select coalesce(sum(cost_krw), 0) from ai_usage_event where byok = false and created_at >= ? and created_at < ?",
                BigDecimal.class, from, to), BigDecimal.ZERO);
        if (total.compareTo(BigDecimal.valueOf(properties.getAlertDailyTotalKrw())) >= 0) {
            notifier.adminAlert("COST_TOTAL", day.toString(), "오늘 AI 비용이 기준을 넘었습니다",
                    String.format("%s 지금까지 AI 비용: %,d원 (기준 %,d원)%n관리자 화면에서 사용자별 비용을 확인해 주세요.",
                            day, total.longValue(), properties.getAlertDailyTotalKrw()));
        }
        jdbc.query("select member_id, sum(cost_krw) as cost from ai_usage_event where byok = false and created_at >= ? and created_at < ? "
                        + "group by member_id having sum(cost_krw) >= ? order by cost desc limit 20",
                rs -> {
                    long memberId = rs.getLong("member_id");
                    long cost = rs.getBigDecimal("cost").longValue();
                    notifier.adminAlert("COST_MEMBER", day + ":" + memberId, "한 사용자의 오늘 AI 비용이 기준을 넘었습니다",
                            String.format("회원 %d: %s AI 비용 %,d원 (기준 %,d원)", memberId, day, cost, properties.getAlertDailyMemberKrw()));
                },
                from, to, BigDecimal.valueOf(properties.getAlertDailyMemberKrw()));
    }
}
