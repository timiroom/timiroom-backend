package com.timiroom.domain.usage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 화면 요청 없이 도는 AI 작업(PR 자동 검사, 변경안 작업 등)의 사용량을 모은다.
 * {@link AiCallService#track}이 범위를 열고, AI 서버 클라이언트는 응답에서 읽은 사용량을 여기에 더한다.
 * 범위는 작업을 실행하는 스레드에만 있다.
 */
@Slf4j
public final class AiUsageScope {

    /** AI 서버에 사용량을 돌려 달라고 알리는 헤더 */
    public static final String TRACKING_HEADER = "X-Timiroom-Usage-Tracking";

    private static final ThreadLocal<List<AiUsageRow>> ROWS = new ThreadLocal<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiUsageScope() {}

    public static boolean active() {
        return ROWS.get() != null;
    }

    static void open() {
        ROWS.set(new ArrayList<>());
    }

    static List<AiUsageRow> close() {
        List<AiUsageRow> rows = ROWS.get();
        ROWS.remove();
        return rows == null ? List.of() : rows;
    }

    /** 응답 본문의 usage 배열을 더한다. */
    public static void addNode(JsonNode usage) {
        List<AiUsageRow> rows = ROWS.get();
        if (rows != null) rows.addAll(AiUsageRow.listFrom(usage));
    }

    /** X-Timiroom-Usage 헤더(base64url JSON)를 더한다. */
    public static void addHeader(String headerValue) {
        if (!active() || headerValue == null || headerValue.isBlank()) return;
        try {
            byte[] json = Base64.getUrlDecoder().decode(headerValue.trim());
            addNode(MAPPER.readTree(new String(json, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.warn("사용량 헤더를 읽지 못했습니다: {}", e.getMessage());
        }
    }
}
