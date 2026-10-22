package com.timiroom.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 예약 작업(정기결제, 비용 경보, 멈춘 생성 정리)은 연동 기능을 켜지 않아도 돌아야 한다.
 * 이전에는 연동 설정(IntegrationAuthorizationConfig)에만 @EnableScheduling이 있어, 연동을 끄면 함께 멈췄다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
