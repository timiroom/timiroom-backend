package com.timiroom.domain.billing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 매일 새벽 정기결제와 해지 예약 정리. PORTONE_ENABLED=true일 때만 돈다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "portone", name = "enabled", havingValue = "true")
public class BillingScheduler {

    private final BillingService billingService;

    @Scheduled(cron = "${portone.billing-cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void run() {
        try {
            int handled = billingService.runDueBilling(Instant.now());
            if (handled > 0) log.info("정기결제 처리 {}건", handled);
        } catch (RuntimeException e) {
            log.error("정기결제 처리 실패", e);
        }
    }
}
