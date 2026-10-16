package com.timiroom.domain.billing.controller;

import com.timiroom.domain.billing.entity.BillingPayment;
import com.timiroom.domain.billing.service.BillingService;
import com.timiroom.domain.member.enums.Role;
import com.timiroom.domain.member.repository.MemberRepository;
import com.timiroom.domain.usage.enums.PlanType;
import com.timiroom.infra.portone.PortOneClient.PortOneException;
import com.timiroom.infra.portone.PortOneWebhookVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 설정 > 요금제·결제. 웹훅만 로그인 없이 받고, 서명이 맞아야 처리한다. */
@Slf4j
@RestController
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;
    private final PortOneWebhookVerifier webhookVerifier;
    private final MemberRepository members;
    private final ObjectMapper objectMapper;

    public record PayRequest(Long paymentMethodId, String billingKey) {}

    public record CardRequest(String billingKey, Boolean makeDefault) {}

    public record CreditRequest(Long amountKrw, Long paymentMethodId, String billingKey) {}

    public record CreditSettingsRequest(Boolean enabled, Long monthlyCapKrw) {}

    public record AdminPlanRequest(String plan, Integer months) {}

    @GetMapping("/api/v1/billing/config")
    public Map<String, Object> config() {
        return billingService.clientConfig();
    }

    @GetMapping("/api/v1/billing/subscription")
    public ResponseEntity<?> subscription(HttpSession session) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(billingService.subscriptionView(memberId));
    }

    @PostMapping("/api/v1/billing/subscribe")
    public ResponseEntity<?> subscribe(HttpSession session, @RequestBody PayRequest request) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(billingService.subscribe(memberId,
                new BillingService.PaySource(request.paymentMethodId(), request.billingKey())));
    }

    @GetMapping("/api/v1/billing/payment-methods")
    public ResponseEntity<?> paymentMethods(HttpSession session) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(Map.of("methods", billingService.paymentMethodViews(memberId)));
    }

    @PostMapping("/api/v1/billing/payment-methods")
    public ResponseEntity<?> addPaymentMethod(HttpSession session, @RequestBody CardRequest request) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(Map.of("methods", billingService.addPaymentMethod(memberId, request.billingKey(),
                Boolean.TRUE.equals(request.makeDefault()))));
    }

    @PostMapping("/api/v1/billing/payment-methods/{id}/default")
    public ResponseEntity<?> setDefault(HttpSession session, @PathVariable Long id) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(Map.of("methods", billingService.setDefault(memberId, id)));
    }

    @DeleteMapping("/api/v1/billing/payment-methods/{id}")
    public ResponseEntity<?> deletePaymentMethod(HttpSession session, @PathVariable Long id) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(Map.of("methods", billingService.deletePaymentMethod(memberId, id)));
    }

    @GetMapping("/api/v1/billing/credits")
    public ResponseEntity<?> credits(HttpSession session) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(billingService.creditView(memberId));
    }

    @PatchMapping("/api/v1/billing/credits/settings")
    public ResponseEntity<?> creditSettings(HttpSession session, @RequestBody CreditSettingsRequest request) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(billingService.configureCredit(memberId, Boolean.TRUE.equals(request.enabled()),
                request.monthlyCapKrw()));
    }

    @PostMapping("/api/v1/billing/credits")
    public ResponseEntity<?> buyCredit(HttpSession session, @RequestBody CreditRequest request) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(billingService.buyCredit(memberId, request.amountKrw(),
                new BillingService.PaySource(request.paymentMethodId(), request.billingKey())));
    }

    @PostMapping("/api/v1/billing/cancel")
    public ResponseEntity<?> cancel(HttpSession session) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(billingService.cancel(memberId));
    }

    @PostMapping("/api/v1/billing/resume")
    public ResponseEntity<?> resume(HttpSession session) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(billingService.resume(memberId));
    }

    @GetMapping("/api/v1/billing/payments")
    public ResponseEntity<?> payments(HttpSession session, @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        Page<BillingPayment> result = billingService.history(memberId, page, size);
        return ResponseEntity.ok(Map.of("content", result.getContent().stream().map(BillingService::paymentView).toList(),
                "totalPages", result.getTotalPages(), "page", result.getNumber()));
    }

    @GetMapping("/api/v1/billing/payments/{id}/receipt")
    public ResponseEntity<?> receipt(HttpSession session, @PathVariable Long id) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(billingService.receipt(memberId, id));
    }

    /** PortOne V2 웹훅. 원문 본문으로 서명을 확인한 뒤, 결제를 다시 조회해 반영한다. */
    @PostMapping("/webhooks/portone")
    public ResponseEntity<Void> webhook(@RequestHeader(value = "webhook-id", required = false) String webhookId,
                                        @RequestHeader(value = "webhook-timestamp", required = false) String timestamp,
                                        @RequestHeader(value = "webhook-signature", required = false) String signature,
                                        @RequestBody String rawBody) {
        if (!webhookVerifier.verify(webhookId, timestamp, signature, rawBody)) {
            log.warn("PortOne 웹훅 서명 불일치");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        try {
            JsonNode event = objectMapper.readTree(rawBody);
            String paymentId = event.path("data").path("paymentId").asText("");
            if (event.path("type").asText("").startsWith("Transaction.") && !paymentId.isBlank()) {
                billingService.syncPayment(paymentId);
            }
        } catch (PortOneException e) {
            log.warn("웹훅 결제 재조회 실패: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (Exception e) {
            log.warn("웹훅 처리 실패: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    /** 관리자: 결제 없이 프로 기간을 주거나 무료로 돌린다. */
    @PatchMapping("/api/v1/admin/members/{targetId}/plan")
    public ResponseEntity<?> adminPlan(HttpSession session, @PathVariable Long targetId,
                                       @RequestBody AdminPlanRequest request) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        boolean admin = members.findById(memberId).map(m -> m.getRole() == Role.ADMIN).orElse(false);
        if (!admin) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "관리자만 바꿀 수 있습니다"));
        PlanType plan;
        try {
            plan = PlanType.valueOf(String.valueOf(request.plan()).toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "요금제는 FREE 또는 PRO입니다"));
        }
        return ResponseEntity.ok(billingService.adminSetPlan(targetId, plan, request.months() == null ? 1 : request.months()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private static Long memberId(HttpSession session) {
        return (Long) session.getAttribute("memberId");
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "로그인이 필요합니다"));
    }
}
