package com.timiroom.domain.billing.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/** 같은 알림을 두 번 보내지 않도록 (종류, 기준값)을 한 번만 남긴다. 서버가 여러 대여도 DB 고유 제약으로 한 곳만 성공한다. */
@Component
@RequiredArgsConstructor
public class NoticeLog {

    private final JdbcTemplate jdbc;

    /** 처음 남기면 true, 이미 있으면 false */
    public boolean firstTime(String kind, String ref) {
        String safeRef = ref.length() > 120 ? ref.substring(0, 120) : ref;
        return jdbc.update("insert into billing_notice(kind, ref, created_at) values (?, ?, ?) on conflict do nothing",
                kind, safeRef, Timestamp.from(Instant.now())) > 0;
    }
}
