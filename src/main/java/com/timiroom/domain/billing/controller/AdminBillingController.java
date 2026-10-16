package com.timiroom.domain.billing.controller;

import com.timiroom.domain.billing.service.AdminBillingService;
import com.timiroom.domain.billing.service.AdminInsightService;
import com.timiroom.domain.billing.service.AdminSystemService;
import com.timiroom.domain.billing.service.RefundService;
import com.timiroom.domain.member.enums.Role;
import com.timiroom.domain.member.repository.MemberRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.function.Supplier;

/** 관리자 화면 API. 회원의 role이 ADMIN일 때만 쓸 수 있다. 요금제 부여는 BillingController에 있다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/billing")
public class AdminBillingController {

    private final AdminBillingService adminBilling;
    private final AdminInsightService insights;
    private final AdminSystemService system;
    private final RefundService refundService;
    private final MemberRepository members;

    public record RefundRequest(Long amountKrw, String reason, Boolean downgrade) {}

    @GetMapping("/overview")
    public ResponseEntity<?> overview(HttpSession session) {
        return asAdmin(session, adminBilling::overview);
    }

    @GetMapping("/insights")
    public ResponseEntity<?> insights(HttpSession session) {
        return asAdmin(session, insights::insights);
    }

    @GetMapping("/members/{memberId}")
    public ResponseEntity<?> member(HttpSession session, @PathVariable Long memberId) {
        return asAdmin(session, () -> insights.memberDetail(memberId));
    }

    @GetMapping("/usage")
    public ResponseEntity<?> usage(HttpSession session, @RequestParam(required = false) String feature,
                                   @RequestParam(required = false) String query,
                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        return asAdmin(session, () -> insights.usageEvents(feature, query, page, size));
    }

    @GetMapping("/system")
    public ResponseEntity<?> system(HttpSession session) {
        return asAdmin(session, system::status);
    }

    @GetMapping("/members")
    public ResponseEntity<?> members(HttpSession session, @RequestParam(required = false) String query,
                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return asAdmin(session, () -> adminBilling.members(query, page, size));
    }

    @GetMapping("/payments")
    public ResponseEntity<?> payments(HttpSession session, @RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return asAdmin(session, () -> adminBilling.payments(status, page, size));
    }

    @PostMapping("/payments/{id}/refund")
    public ResponseEntity<?> refund(HttpSession session, @PathVariable Long id, @RequestBody RefundRequest request) {
        return asAdmin(session, () -> refundService.adminRefund(id, request.amountKrw(), request.reason(),
                Boolean.TRUE.equals(request.downgrade())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private ResponseEntity<?> asAdmin(HttpSession session, Supplier<Object> work) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "로그인이 필요합니다"));
        }
        boolean admin = members.findById(memberId).map(m -> m.getRole() == Role.ADMIN).orElse(false);
        if (!admin) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "관리자만 볼 수 있습니다"));
        return ResponseEntity.ok(work.get());
    }
}
