package com.timiroom.domain.billing.service;

import com.timiroom.domain.aikey.service.SecretCipher;
import com.timiroom.infra.mail.MailService;
import com.timiroom.infra.portone.PortOneProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final MeterRegistry meters;
    private final WebClient http = WebClient.builder().build();
    private final String pipelineUrl;
    private final String consistencyUrl;
    private final boolean testMode;
    private final boolean usageEnforced;

    public AdminSystemService(JdbcTemplate jdbc, PortOneProperties portOne, SecretCipher cipher, MailService mail, MeterRegistry meters,
                              @Value("${rag-pipeline.base-url}") String pipelineUrl,
                              @Value("${consistency-service.base-url}") String consistencyUrl,
                              @Value("${portone.test-mode:true}") boolean testMode,
                              @Value("${usage.enforce:true}") boolean usageEnforced) {
        this.jdbc = jdbc;
        this.portOne = portOne;
        this.cipher = cipher;
        this.mail = mail;
        this.meters = meters;
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
        view.put("server", serverMetrics());
        return view;
    }

    /**
     * 지금 응답한 백엔드 서버 한 대의 지표(서버가 여러 대면 대마다 다르다). 요청 수는 서버가 켜진 뒤부터 센 값이다.
     */
    private Map<String, Object> serverMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instance", hostName());
        m.put("uptimeSeconds", gauge("process.uptime"));
        m.put("processCpuPercent", percent(gauge("process.cpu.usage")));
        m.put("systemCpuPercent", percent(gauge("system.cpu.usage")));
        m.put("cpuCount", gauge("system.cpu.count"));
        m.put("heapUsedMb", mb(sum("jvm.memory.used", "area", "heap")));
        m.put("heapMaxMb", mb(sum("jvm.memory.max", "area", "heap")));
        m.put("threads", gauge("jvm.threads.live"));
        m.put("dbActive", gauge("hikaricp.connections.active"));
        m.put("dbIdle", gauge("hikaricp.connections.idle"));
        m.put("dbMax", gauge("hikaricp.connections.max"));

        long total = 0;
        long serverErrors = 0;
        long clientErrors = 0;
        double totalMs = 0;
        double maxMs = 0;
        Map<String, long[]> byUri = new HashMap<>();
        Map<String, double[]> timeByUri = new HashMap<>();
        for (Timer timer : meters.find("http.server.requests").timers()) {
            String uri = Objects.requireNonNullElse(timer.getId().getTag("uri"), "UNKNOWN");
            // 헬스 체크와 어느 경로에도 맞지 않은 요청(봇, 잘못된 주소)은 지표에서 뺀다.
            if (uri.startsWith("/actuator") || "UNKNOWN".equals(uri) || "/**".equals(uri) || "root".equals(uri)) continue;
            String status = Objects.requireNonNullElse(timer.getId().getTag("status"), "");
            long count = timer.count();
            double ms = timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS);
            total += count;
            totalMs += ms;
            maxMs = Math.max(maxMs, timer.max(java.util.concurrent.TimeUnit.MILLISECONDS));
            if (status.startsWith("5")) serverErrors += count;
            if (status.startsWith("4")) clientErrors += count;
            String key = Objects.requireNonNullElse(timer.getId().getTag("method"), "") + " " + uri;
            byUri.computeIfAbsent(key, k -> new long[2])[0] += count;
            if (status.startsWith("5")) byUri.get(key)[1] += count;
            timeByUri.computeIfAbsent(key, k -> new double[1])[0] += ms;
        }
        m.put("requests", total);
        m.put("serverErrors", serverErrors);
        m.put("clientErrors", clientErrors);
        m.put("errorPercent", total == 0 ? 0 : Math.round(serverErrors * 1000.0 / total) / 10.0);
        m.put("avgMs", total == 0 ? 0 : Math.round(totalMs / total));
        m.put("maxMs", Math.round(maxMs));
        m.put("requestsPerMinute", total == 0 || gauge("process.uptime") == 0 ? 0
                : Math.round(total / (gauge("process.uptime") / 60.0) * 10) / 10.0);
        m.put("topEndpoints", byUri.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(10)
                .map(e -> Map.<String, Object>of("endpoint", e.getKey(), "count", e.getValue()[0], "errors", e.getValue()[1],
                        "avgMs", e.getValue()[0] == 0 ? 0 : Math.round(timeByUri.get(e.getKey())[0] / e.getValue()[0])))
                .toList());
        return m;
    }

    private double gauge(String name) {
        var gauge = meters.find(name).gauge();
        if (gauge != null) return gauge.value();
        double total = 0;
        for (var g : meters.find(name).gauges()) total += g.value();
        return Double.isNaN(total) ? 0 : total;
    }

    private double sum(String name, String tag, String value) {
        double total = 0;
        for (var g : meters.find(name).tag(tag, value).gauges()) {
            if (!Double.isNaN(g.value()) && g.value() > 0) total += g.value();
        }
        return total;
    }

    private static double percent(double ratio) {
        return Double.isNaN(ratio) || ratio < 0 ? 0 : Math.round(ratio * 1000) / 10.0;
    }

    private static long mb(double bytes) {
        return Math.round(bytes / 1024 / 1024);
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
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
