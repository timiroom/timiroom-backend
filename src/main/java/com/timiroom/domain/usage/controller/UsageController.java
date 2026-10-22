package com.timiroom.domain.usage.controller;

import com.timiroom.domain.aikey.service.LlmCredentialService;
import com.timiroom.domain.usage.UsageProperties;
import com.timiroom.domain.usage.enums.PlanType;
import com.timiroom.domain.usage.service.ModelTierService;
import com.timiroom.domain.usage.service.UsageService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 내 사용량. */
@RestController
@RequestMapping("/api/v1/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageService usageService;
    private final UsageProperties properties;
    private final LlmCredentialService credentialService;
    private final ModelTierService modelTiers;

    public record TierRequest(String tier) {}

    /**
     * 내 사용량. 다른 AI 서비스처럼 금액·토큰은 보여 주지 않고 비율과 초기화 시각만 준다.
     * (원 단위 비용은 서버 안에서 한도 계산에만 쓴다.)
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) return unauthorized();
        UsageService.Status status = usageService.statusOf(memberId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("plan", status.plan().name());
        body.put("planName", status.planName());
        body.put("percent", status.percent());
        body.put("creditBalanceKrw", status.creditKrw().setScale(0, java.math.RoundingMode.DOWN).longValue());
        body.put("resetAt", status.period().end());
        body.put("usingOwnKey", credentialService.activeKey(memberId).isPresent());
        body.put("enforced", properties.isEnforce());
        return ResponseEntity.ok(body);
    }

    /** 이번 기간 기능별 비중(%) */
    @GetMapping("/me/features")
    public ResponseEntity<?> features(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(Map.of("features", usageService.featureShares(memberId)));
    }

    /** 내 AI 품질(빠름·균형·정밀)과 고를 수 있는 선택지 */
    @GetMapping("/model-tier")
    public ResponseEntity<?> modelTier(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(modelTiers.view(memberId));
    }

    @PutMapping("/model-tier")
    public ResponseEntity<?> chooseModelTier(HttpSession session, @RequestBody TierRequest request) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) return unauthorized();
        try {
            modelTiers.choose(memberId, request.tier());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(modelTiers.view(memberId));
    }

    /** 요금제 비교 화면. 한도는 무료 대비 배수로만 알려 준다. */
    @GetMapping("/plans")
    public Map<String, Object> plans() {
        long freeLimit = Math.max(1, properties.plan(PlanType.FREE).getMonthlyLimitKrw());
        return Map.of("plans", List.of(planView(PlanType.FREE, freeLimit), planView(PlanType.PRO, freeLimit)));
    }

    private Map<String, Object> planView(PlanType type, long freeLimit) {
        UsageProperties.Plan plan = properties.plan(type);
        return Map.of("plan", type.name(), "name", plan.getName(), "priceKrw", plan.getPriceKrw(),
                "usageMultiplier", Math.max(1, Math.round((double) plan.getMonthlyLimitKrw() / freeLimit)));
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "로그인이 필요합니다"));
    }
}
