package com.rag.pipeline.phase2.state;

import com.rag.pipeline.phase1.form.PlatformType;
import com.rag.pipeline.phase1.form.ProblemDefinition;
import com.rag.pipeline.phase1.form.TargetUser;
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

    // ── Phase 1 폼 데이터 ────────────────────────────────────────
    private final String sessionId;
    private final String projectName;
    private final PlatformType platform;
    private final List<String> techStack;
    private final ProblemDefinition problemDefinition;
    private final List<TargetUser> targetUsers;

    /** MoSCoW 기준 분리된 기능 목록 */
    private final List<String> mustFeatures;
    private final List<String> shouldFeatures;
    private final List<String> couldFeatures;
    private final List<String> excludedFeatures;

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

    /** Search 에이전트가 수집한 시장 조사 데이터 */
    private final String marketResearch;

    /** DBA 에이전트가 PRD에 요청하는 피드백 (rollback 트리거) */
    @Builder.Default
    private final String prdFeedbackFromDba = "";

    /** API 에이전트가 PRD에 요청하는 피드백 (rollback 트리거) */
    @Builder.Default
    private final String prdFeedbackFromApi = "";

    /** rollback 횟수 (무한루프 방지, 최대 2회) */
    @Builder.Default
    private final int rollbackCount = 0;

    // ── RL 관련 ─────────────────────────────────────────────────
    /** QaAgent가 계산한 품질 점수 (0.0~1.0) — AgentRLService에서 baseline 계산에 사용 */
    @Builder.Default
    private final double qaQualityScore = 0.0;

    // ── 상태 메시지 (SSE 전송용) ─────────────────────────────────
    private final String statusMessage;

    // ── LangGraph4j Map 변환 ─────────────────────────────────────

    public Map<String, Object> toMap() {
        return Map.ofEntries(
                Map.entry("sessionId",            orEmpty(sessionId)),
                Map.entry("projectName",          orEmpty(projectName)),
                Map.entry("platform",             platform != null ? platform.name() : ""),
                Map.entry("techStack",            techStack != null ? techStack : List.of()),
                Map.entry("mustFeatures",         mustFeatures != null ? mustFeatures : List.of()),
                Map.entry("shouldFeatures",       shouldFeatures != null ? shouldFeatures : List.of()),
                Map.entry("couldFeatures",        couldFeatures != null ? couldFeatures : List.of()),
                Map.entry("excludedFeatures",     excludedFeatures != null ? excludedFeatures : List.of()),
                Map.entry("userQuery",            orEmpty(userQuery)),
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
                Map.entry("prdDocument",          orEmpty(prdDocument)),
                Map.entry("marketResearch",       orEmpty(marketResearch)),
                Map.entry("prdFeedbackFromDba",   orEmpty(prdFeedbackFromDba)),
                Map.entry("prdFeedbackFromApi",   orEmpty(prdFeedbackFromApi)),
                Map.entry("rollbackCount",        String.valueOf(rollbackCount))
        );
    }

    @SuppressWarnings("unchecked")
    public static PipelineState fromMap(Map<String, Object> map) {
        return PipelineState.builder()
                .sessionId((String) map.getOrDefault("sessionId", ""))
                .projectName((String) map.getOrDefault("projectName", ""))
                .platform(parsePlatform((String) map.getOrDefault("platform", "")))
                .techStack((List<String>) map.getOrDefault("techStack", List.of()))
                .mustFeatures((List<String>) map.getOrDefault("mustFeatures", List.of()))
                .shouldFeatures((List<String>) map.getOrDefault("shouldFeatures", List.of()))
                .couldFeatures((List<String>) map.getOrDefault("couldFeatures", List.of()))
                .excludedFeatures((List<String>) map.getOrDefault("excludedFeatures", List.of()))
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
                .marketResearch((String) map.getOrDefault("marketResearch", ""))
                .prdFeedbackFromDba((String) map.getOrDefault("prdFeedbackFromDba", ""))
                .prdFeedbackFromApi((String) map.getOrDefault("prdFeedbackFromApi", ""))
                .rollbackCount(Integer.parseInt(
                        String.valueOf(map.getOrDefault("rollbackCount", "0"))))
                .build();
    }

    private static PlatformType parsePlatform(String s) {
        if (s == null || s.isBlank()) return null;
        try { return PlatformType.valueOf(s); } catch (Exception e) { return null; }
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }
}