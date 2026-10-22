package com.timiroom.domain.usage.service;

import lombok.Getter;

import java.time.Instant;

/** 이번 기간 한도를 다 써서 AI 기능을 막을 때. 화면은 resetAt과 요금제를 보고 안내한다. */
@Getter
public class UsageLimitExceededException extends RuntimeException {

    private final Instant resetAt;
    private final String plan;

    public UsageLimitExceededException(Instant resetAt, String plan) {
        super("이번 달 AI 사용 한도를 모두 썼습니다.");
        this.resetAt = resetAt;
        this.plan = plan;
    }
}
