package com.timiroom.domain.usage.controller;

import com.timiroom.domain.aikey.service.LlmCredentialService;
import com.timiroom.domain.usage.UsageProperties;
import com.timiroom.domain.usage.entity.UsageEvent;
import com.timiroom.domain.usage.enums.PlanType;
import com.timiroom.domain.usage.service.UsageService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 내 사용량 화면 데이터. */
@RestController
@RequestMapping("/api/v1/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageService usageService;
    private final UsageProperties properties;
    private final LlmCredentialService credentialService;

    /** 계정 메뉴·경고 표시에 쓰는 가벼운 요약 */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) return unauthorized();
        UsageService.Status status = usageService.statusOf(memberId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan", status.plan().name());
        body.put("planName", status.planName());
        body.put("limitKrw", status.limitKrw());
        body.put("usedKrw", status.usedKrw());
        body.put("remainingKrw", status.remainingKrw());
        body.put("percent", status.percent());
        body.put("periodStart", status.period().start());
        body.put("resetAt", status.period().end());
        body.put("usingOwnKey", credentialService.activeKey(memberId).isPresent());
        body.put("enforced", properties.isEnforce());
        return ResponseEntity.ok(body);
    }

    /** 사용량 화면: 기능별·날짜별 합계 */
    @GetMapping("/me/breakdown")
    public ResponseEntity<?> breakdown(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(usageService.breakdown(memberId));
    }

    /** 최근 사용 내역 */
    @GetMapping("/me/events")
    public ResponseEntity<?> events(HttpSession session, @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) return unauthorized();
        Page<UsageEvent> events = usageService.recent(memberId, page, size);
        List<Map<String, Object>> rows = events.getContent().stream().map(e -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("feature", e.getFeature().name());
            row.put("projectId", e.getProjectId());
            row.put("model", e.getModel());
            row.put("provider", e.getProvider());
            row.put("inputTokens", e.getInputTokens());
            row.put("outputTokens", e.getOutputTokens());
            row.put("costKrw", e.getCostKrw().setScale(1, RoundingMode.HALF_UP));
            row.put("ownKey", e.isByok());
            row.put("counted", e.isCounted());
            row.put("createdAt", e.getCreatedAt());
            return row;
        }).toList();
        return ResponseEntity.ok(Map.of("content", rows, "totalPages", events.getTotalPages(), "page", events.getNumber()));
    }

    /** 요금제 비교 화면 */
    @GetMapping("/plans")
    public Map<String, Object> plans() {
        return Map.of("plans", List.of(planView(PlanType.FREE), planView(PlanType.PRO)));
    }

    private Map<String, Object> planView(PlanType type) {
        UsageProperties.Plan plan = properties.plan(type);
        return Map.of("plan", type.name(), "name", plan.getName(),
                "priceKrw", plan.getPriceKrw(), "monthlyLimitKrw", plan.getMonthlyLimitKrw());
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "로그인이 필요합니다"));
    }
}
