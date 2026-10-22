package com.timiroom.domain.aikey.controller;

import com.timiroom.domain.aikey.LlmProvider;
import com.timiroom.domain.aikey.service.LlmCredentialService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 설정 > 내 AI 키. 키 원문은 받기만 하고 돌려주지 않는다. */
@RestController
@RequestMapping("/api/v1/ai-keys")
@RequiredArgsConstructor
public class LlmCredentialController {

    private final LlmCredentialService service;

    public record RegisterRequest(String provider, String baseUrl, String model, String apiKey) {}

    public record UpdateRequest(Boolean active, String model) {}

    @GetMapping
    public ResponseEntity<?> list(HttpSession session) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(Map.of("keys", service.list(memberId), "providers", providers()));
    }

    @PostMapping
    public ResponseEntity<?> register(HttpSession session, @RequestBody RegisterRequest request) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(service.register(memberId, request.provider(), request.baseUrl(), request.model(), request.apiKey()));
    }

    @PostMapping("/{id}/verify")
    public ResponseEntity<?> verify(HttpSession session, @PathVariable Long id) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(service.verify(memberId, id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(HttpSession session, @PathVariable Long id, @RequestBody UpdateRequest request) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(service.update(memberId, id, request.active(), request.model()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpSession session, @PathVariable Long id) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        service.delete(memberId, id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private static List<Map<String, Object>> providers() {
        return Arrays.stream(LlmProvider.values()).map(p -> Map.<String, Object>of(
                "id", p.name(), "baseUrl", p.defaultBaseUrl() == null ? "" : p.defaultBaseUrl(),
                "custom", p == LlmProvider.CUSTOM)).toList();
    }

    private static Long memberId(HttpSession session) {
        return (Long) session.getAttribute("memberId");
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "로그인이 필요합니다"));
    }
}
