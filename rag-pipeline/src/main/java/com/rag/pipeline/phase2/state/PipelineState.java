package com.rag.pipeline.phase2.state;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * Phase 2 전체에서 공유되는 LangGraph4j State 객체
 * 모든 에이전트가 이 State를 읽고 쓰면서 결과를 누적합니다.
 * 불변 객체로 설계하여 각 노드가 새 인스턴스를 반환합니다.
 */
@Getter
@Builder(toBuilder = true)
public class PipelineState {

    // ── Phase 1 결과 ────────────────────────────────────────────
    /** 사용자 원본 입력 */
    private final String userQuery;

    /** Phase 1 RAG가 조립한 컨텍스트 프롬프트 */
    private final String contextPrompt;

    // ── PM 에이전트 결과 ─────────────────────────────────────────
    /** PM 에이전트가 도출한 기능 목록 */
    private final List<String> featureList;

    /** DBA 에이전트에 내릴 지시사항 */
    private final String dbaInstruction;

    /** API 에이전트에 내릴 지시사항 */
    private final String apiInstruction;

    // ── 병렬 에이전트 결과 ───────────────────────────────────────
    /** DBA 에이전트가 설계한 DB 스키마 (JSON) */
    private final String dbSchema;

    /** API 에이전트가 설계한 API 스펙 (JSON) */
    private final String apiSpec;

    // ── Phase 3 검증 관련 ────────────────────────────────────────
    /** 현재 재시도 횟수 */
    @Builder.Default
    private final int retryCount = 0;

    /** PRD 에이전트가 생성한 PRD 문서 (JSON) */
    private final String prdDocument;

    /** 마지막 검증 실패 원인 (Retry 재주입용) */
    private final String lastValidationError;

    /** 최종 검증 통과 여부 */
    @Builder.Default
    private final boolean validated = false;

    // ── 상태 메시지 (SSE 전송용) ─────────────────────────────────
    private final String statusMessage;

    // ── LangGraph4j Map 변환 ─────────────────────────────────────

    public Map<String, Object> toMap() {
        return Map.ofEntries(
                Map.entry("userQuery",           orEmpty(userQuery)),
                Map.entry("contextPrompt",        orEmpty(contextPrompt)),
                Map.entry("featureList",          featureList != null ? featureList : List.of()),
                Map.entry("dbaInstruction",       orEmpty(dbaInstruction)),
                Map.entry("apiInstruction",       orEmpty(apiInstruction)),
                Map.entry("dbSchema",             orEmpty(dbSchema)),
                Map.entry("apiSpec",              orEmpty(apiSpec)),
                Map.entry("retryCount",           retryCount),
                Map.entry("lastValidationError",  orEmpty(lastValidationError)),
                Map.entry("validated",            validated),
                Map.entry("statusMessage",        orEmpty(statusMessage)),
                Map.entry("prdDocument", orEmpty(prdDocument))
        );
    }

    @SuppressWarnings("unchecked")
    public static PipelineState fromMap(Map<String, Object> map) {
        return PipelineState.builder()
                .userQuery((String) map.getOrDefault("userQuery", ""))
                .contextPrompt((String) map.getOrDefault("contextPrompt", ""))
                .featureList((List<String>) map.getOrDefault("featureList", List.of()))
                .dbaInstruction((String) map.getOrDefault("dbaInstruction", ""))
                .apiInstruction((String) map.getOrDefault("apiInstruction", ""))
                .dbSchema((String) map.getOrDefault("dbSchema", ""))
                .apiSpec((String) map.getOrDefault("apiSpec", ""))
                .retryCount((int) map.getOrDefault("retryCount", 0))
                .lastValidationError((String) map.getOrDefault("lastValidationError", ""))
                .validated((boolean) map.getOrDefault("validated", false))
                .statusMessage((String) map.getOrDefault("statusMessage", ""))
                .prdDocument((String) map.getOrDefault("prdDocument", ""))
                .build();
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }
}