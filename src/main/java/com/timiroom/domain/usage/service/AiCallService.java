package com.timiroom.domain.usage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.aikey.service.LlmCredentialService;
import com.timiroom.domain.usage.enums.UsageFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * AI 서버를 부르는 모든 기능이 거치는 공통 단계.
 *   1) 사용자 키가 있는지 보고 한도를 확인한다
 *   2) AI 서버에 넘길 헤더(사용량 추적, 사용자 키)를 만든다
 *   3) 끝난 뒤 AI 서버가 돌려준 사용량을 기록한다
 * 내부 토큰은 AI 서버 클라이언트가 기본 헤더로 붙인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCallService {

    public static final String USAGE_HEADER = "X-Timiroom-Usage";

    private final UsageService usageService;
    private final LlmCredentialService credentialService;
    private final ObjectMapper objectMapper;

    /** 한 번의 AI 기능 실행. headers를 AI 서버 요청에 그대로 붙인다. */
    public record AiCall(Long memberId, Long projectId, UsageFeature feature, boolean usingOwnKey,
                         Map<String, String> headers) {}

    /** @throws UsageLimitExceededException 한도를 다 썼을 때 */
    public AiCall begin(Long memberId, Long projectId, UsageFeature feature) {
        var activeKey = credentialService.activeKey(memberId);
        usageService.requireAllowance(memberId, feature, activeKey.isPresent());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Timiroom-Usage-Tracking", "1");
        activeKey.ifPresent(key -> {
            headers.put("X-Timiroom-Llm-Provider", key.provider());
            headers.put("X-Timiroom-Llm-Base-Url", key.baseUrl());
            headers.put("X-Timiroom-Llm-Api-Key", key.apiKey());
            headers.put("X-Timiroom-Llm-Model", key.model());
        });
        return new AiCall(memberId, projectId, feature, activeKey.isPresent(), headers);
    }

    /** 자동으로 돌아 사용자 키를 쓰지 않는 작업(PR 자동 검사 등). 한도는 확인하지 않고 기록만 한다. */
    public AiCall background(Long memberId, Long projectId, UsageFeature feature) {
        return new AiCall(memberId, projectId, feature, false, Map.of("X-Timiroom-Usage-Tracking", "1"));
    }

    /**
     * 화면 요청 없이 도는 AI 작업을 memberId 앞으로 기록한다. 서버 키로만 돌기 때문에 한도를 확인하고,
     * 작업이 실패해도 이미 쓴 토큰은 남긴다. 이미 범위 안이면 바깥 범위가 모아 기록한다.
     * @throws UsageLimitExceededException 한도를 다 썼을 때(작업을 시작하지 않는다)
     */
    public <T> T track(Long memberId, Long projectId, UsageFeature feature, String reference, Supplier<T> work) {
        if (AiUsageScope.active()) return work.get();
        usageService.requireAllowance(memberId, feature, false);
        AiUsageScope.open();
        try {
            return work.get();
        } finally {
            usageService.record(memberId, projectId, feature, AiUsageScope.close(), reference, true);
        }
    }

    public void finish(AiCall call, List<AiUsageRow> rows, String reference) {
        finish(call, rows, reference, true);
    }

    public void finish(AiCall call, List<AiUsageRow> rows, String reference, boolean counted) {
        if (call == null) return;
        usageService.record(call.memberId(), call.projectId(), call.feature(), rows, reference, counted);
    }

    /** X-Timiroom-Usage 헤더(base64url JSON)를 읽는다. 없거나 깨졌으면 빈 목록. */
    public List<AiUsageRow> parseHeader(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) return List.of();
        try {
            byte[] json = Base64.getUrlDecoder().decode(headerValue.trim());
            return AiUsageRow.listFrom(objectMapper.readTree(new String(json, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.warn("사용량 헤더를 읽지 못했습니다: {}", e.getMessage());
            return List.of();
        }
    }

    public List<AiUsageRow> parseNode(JsonNode node) {
        return AiUsageRow.listFrom(node);
    }
}
