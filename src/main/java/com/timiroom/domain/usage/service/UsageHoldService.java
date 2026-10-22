package com.timiroom.domain.usage.service;

import com.timiroom.domain.usage.UsageProperties;
import com.timiroom.domain.usage.enums.UsageFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * 동시에 여러 AI 호출을 시작해 한도를 넘기지 않도록, 시작할 때 남은 양에서 예상 금액을 미리 잡아 둔다.
 *   시작: 회원별 DB 잠금 → (남은 양 - 진행 중인 예약)이 기능별 최소 금액보다 크면 예약을 남기고 시작한다.
 *   끝: 사용량을 기록하고 예약을 지운다. 끝내지 못한 호출의 예약은 holdTtl이 지나면 무시한다.
 * 호출이 오래 걸려도 잠금은 예약을 남기는 동안만 잡도록 별도 트랜잭션으로 처리한다.
 */
@Slf4j
@Service
public class UsageHoldService {

    private final UsageProperties properties;
    private final UsageService usageService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate separate;

    public UsageHoldService(UsageProperties properties, UsageService usageService, JdbcTemplate jdbc,
                            PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.usageService = usageService;
        this.jdbc = jdbc;
        this.separate = new TransactionTemplate(transactionManager);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 시작해도 되는지 확인하고 예약한다. 한도 검사를 하지 않는 경우(사용자 키, 검사 꺼짐)는 null.
     * @throws UsageLimitExceededException 남은 양이 모자랄 때
     */
    public Long reserve(Long memberId, UsageFeature feature, boolean usingOwnKey) {
        if (!properties.isEnforce() || usingOwnKey || memberId == null) return null;
        return separate.execute(tx -> {
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 3))", Object.class, "usage:" + memberId);
            Instant now = Instant.now();
            jdbc.update("delete from ai_usage_hold where member_id = ? and expires_at <= ?", memberId, Timestamp.from(now));
            BigDecimal held = Objects.requireNonNullElse(jdbc.queryForObject(
                    "select coalesce(sum(amount_krw), 0) from ai_usage_hold where member_id = ?", BigDecimal.class, memberId),
                    BigDecimal.ZERO);
            UsageService.Status status = usageService.statusOf(memberId);
            BigDecimal reserve = properties.reserveOf(feature);
            if (usageService.availableOf(status).subtract(held).compareTo(reserve) <= 0) {
                throw new UsageLimitExceededException(status.period().end(), status.plan().name());
            }
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "insert into ai_usage_hold(member_id, feature, amount_krw, created_at, expires_at) values (?, ?, ?, ?, ?)",
                        new String[]{"usage_hold_id"});
                ps.setLong(1, memberId);
                ps.setString(2, feature.name());
                ps.setBigDecimal(3, reserve);
                ps.setTimestamp(4, Timestamp.from(now));
                ps.setTimestamp(5, Timestamp.from(now.plus(properties.getHoldTtl())));
                return ps;
            }, key);
            Number id = key.getKey();
            return id == null ? null : id.longValue();
        });
    }

    /** 사용량 기록이 끝난 호출의 예약을 지운다. */
    public void release(Long holdId) {
        if (holdId == null) return;
        try {
            jdbc.update("delete from ai_usage_hold where usage_hold_id = ?", holdId);
        } catch (RuntimeException e) {
            log.warn("사용량 예약 정리 실패 | holdId: {}", holdId);
        }
    }

    /** 시작한 요청과 끝난 결과가 따로 오는 작업(프로젝트 생성)은 그 회원의 같은 기능 예약 중 가장 오래된 것을 지운다. */
    public void releaseOldest(Long memberId, UsageFeature feature) {
        if (memberId == null) return;
        try {
            jdbc.update("delete from ai_usage_hold where usage_hold_id = (select usage_hold_id from ai_usage_hold "
                    + "where member_id = ? and feature = ? order by created_at limit 1)", memberId, feature.name());
        } catch (RuntimeException e) {
            log.warn("사용량 예약 정리 실패 | memberId: {}", memberId);
        }
    }
}
