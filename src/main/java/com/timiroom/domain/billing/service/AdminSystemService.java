package com.timiroom.domain.billing.service;

import com.timiroom.domain.aikey.service.SecretCipher;
import com.timiroom.infra.mail.MailService;
import com.timiroom.infra.portone.PortOneProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** 관리자 > 시스템: 연결된 서비스 상태, 결제·메일 설정, 작업 큐와 최근 실패. 비밀 값은 내보내지 않는다. */
@Slf4j
@Service
public class AdminSystemService {

    private final JdbcTemplate jdbc;
    private final PortOneProperties portOne;
    private final SecretCipher cipher;
    private final MailService mail;
    private final WebClient http = WebClient.builder().build();
    private final String pipelineUrl;
    private final String consistencyUrl;
    private final boolean testMode;
    private final boolean usageEnforced;

    public AdminSystemService(JdbcTemplate jdbc, PortOneProperties portOne, SecretCipher cipher, MailService mail,
                              @Value("${rag-pipeline.base-url}") String pipelineUrl,
                              @Value("${consistency-service.base-url}") String consistencyUrl,
                              @Value("${portone.test-mode:true}") boolean testMode,
                              @Value("${usage.enforce:true}") boolean usageEnforced) {
        this.jdbc = jdbc;
        this.portOne = portOne;
        this.cipher = cipher;
        this.mail = mail;
        this.pipelineUrl = pipelineUrl;
        this.consistencyUrl = consistencyUrl;
        this.testMode = testMode;
        this.usageEnforced = usageEnforced;
    }

    public Map<String, Object> status() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("checkedAt", Instant.now());
        view.put("services", List.of(
                service("백엔드", null),
                service("AI 서버", pipelineUrl),
                service("정합성 서비스", consistencyUrl)));
        view.put("settings", List.of(
                setting("결제(PortOne)", portOne.isReady(), portOne.isReady() ? (testMode ? "테스트 결제" : "실결제") : "꺼짐"),
                setting("암호화 키", cipher.isEnabled(), cipher.isEnabled() ? "설정됨" : "없음(AI 키·결제 꺼짐)"),
                setting("알림 메일", mail.isEnabled(), mail.isEnabled() ? "SMTP 연결" : "설정 안 됨"),
                setting("사용량 한도", usageEnforced, usageEnforced ? "적용 중" : "꺼짐")));
        view.put("jobs", jdbc.queryForList(
                "select kind, state, count(*) as count from integration_job where created_at >= now() - interval '7 days' "
                        + "group by kind, state order by kind, state"));
        view.put("generations", jdbc.queryForList(
                "select status, count(*) as count from pipeline_execution where created_at >= current_date - 6 group by status order by status"));
        view.put("recentFailures", jdbc.queryForList(
                "(select 'generation' as source, created_at::timestamptz as at, coalesce(error_message, '') as message "
                        + " from pipeline_execution where status = 'FAILED' order by created_at desc limit 5) "
                        + "union all (select 'job' as source, created_at as at, coalesce(kind || ' ' || error_code, kind) as message "
                        + " from integration_job where state = 'FAILED' order by created_at desc limit 5) "
                        + "union all (select 'payment' as source, created_at as at, coalesce(failure_reason, '') as message "
                        + " from billing_payment where status = 'FAILED' order by created_at desc limit 5) "
                        + "order by at desc limit 10"));
        view.put("activeHolds", jdbc.queryForObject("select count(*) from ai_usage_hold where expires_at > now()", Long.class));
        return view;
    }

    private Map<String, Object> service(String name, String baseUrl) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        if (baseUrl == null) {
            row.put("up", true);
            row.put("latencyMs", 0);
            return row;
        }
        long started = System.nanoTime();
        try {
            String body = http.get().uri(baseUrl.replaceAll("/+$", "") + "/actuator/health")
                    .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(3));
            row.put("up", body != null && body.contains("UP"));
        } catch (RuntimeException e) {
            row.put("up", false);
            row.put("error", e.getClass().getSimpleName());
        }
        row.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
        return row;
    }

    private static Map<String, Object> setting(String name, boolean ok, String detail) {
        return Map.of("name", name, "ok", ok, "detail", detail);
    }
}
