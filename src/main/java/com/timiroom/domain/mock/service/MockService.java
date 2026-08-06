package com.timiroom.domain.mock.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.mock.entity.MockApiLog;
import com.timiroom.domain.mock.repository.MockApiLogRepository;
import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import com.timiroom.domain.pipeline.service.PipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock 서버 — 파이프라인이 생성한 API 명세를 실제 호출 가능한 가상 엔드포인트로 제공
 *
 * 동작:
 *   1. projectId로 최신 API_SPEC 아티팩트를 조회
 *   2. 요청 method + path를 명세의 엔드포인트와 매칭 (path 템플릿 {id} 지원)
 *   3. 매칭된 엔드포인트의 successResponse 스키마로 가짜 응답 생성
 *
 * 백엔드 구현이 끝나기 전에 프론트엔드가 개발을 시작할 수 있게 하는 것이 목적.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MockService {

    private final PipelineService pipelineService;
    private final MockApiLogRepository logRepository;
    private final ObjectMapper objectMapper;

    /** 필드 타입 문자열에서 주석(" // 설명")을 떼어낸다 */
    private static final Pattern TYPE_COMMENT = Pattern.compile("\\s*//.*$");

    /** path 템플릿의 {param} 부분 */
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^/}]+}");

    /**
     * Mock 요청 처리 결과
     *
     * @param statusCode  응답 상태 코드
     * @param body        응답 본문
     * @param matchedPath 매칭된 명세의 path 템플릿 (실패 시 null)
     */
    public record MockResult(int statusCode, Map<String, Object> body, String matchedPath) {}

    /**
     * 요청을 명세와 매칭해 가짜 응답을 만든다.
     *
     * @param projectId    프로젝트 ID
     * @param method       HTTP 메서드
     * @param endpoint     요청 경로 (mock 프리픽스가 제거된 상태, 예: /api/v1/users/3)
     * @param forcedStatus X-Mock-Error 헤더로 지정된 강제 상태 코드 (없으면 null)
     */
    public MockResult handle(Long projectId, String method, String endpoint, Integer forcedStatus) {
        Optional<JsonNode> matched = findEndpoint(projectId, method, endpoint);

        if (matched.isEmpty()) {
            return new MockResult(404, Map.of(
                    "error", "Mock API Not Found",
                    "message", "프로젝트 " + projectId + "의 API 명세에 "
                            + method + " " + endpoint + " 와 일치하는 엔드포인트가 없습니다."
            ), null);
        }

        JsonNode spec = matched.get();
        String matchedPath = spec.path("path").asText("");

        // X-Mock-Error로 강제 에러를 요청한 경우, 명세는 매칭됐지만 에러를 반환
        if (forcedStatus != null) {
            return new MockResult(forcedStatus, Map.of(
                    "status", forcedStatus,
                    "error", "Forced Error",
                    "message", "X-Mock-Error 헤더로 요청된 강제 " + forcedStatus + " 응답입니다."
            ), matchedPath);
        }

        Map<String, Object> body = buildResponseBody(spec.path("successResponse"));
        int status = "POST".equalsIgnoreCase(method) ? 201 : 200;
        return new MockResult(status, body, matchedPath);
    }

    /** 호출 로그 저장 — 실패해도 Mock 응답 자체를 막지 않는다 */
    @Transactional
    public void log(Long projectId, String method, String endpoint,
                    int statusCode, long latencyMs, String matchedPath) {
        try {
            logRepository.save(MockApiLog.builder()
                    .projectId(projectId)
                    .method(method)
                    .endpoint(truncate(endpoint, 500))
                    .statusCode(statusCode)
                    .latencyMs(latencyMs)
                    .matchedPath(truncate(matchedPath, 500))
                    .build());
        } catch (Exception e) {
            log.warn("Mock 호출 로그 저장 실패 | projectId: {}, error: {}", projectId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<MockApiLog> getLogs(Long projectId, org.springframework.data.domain.Pageable pageable) {
        return logRepository.findByProjectIdOrderByCreatedAtDesc(projectId, pageable);
    }

    /** 프로젝트의 최신 API_SPEC 아티팩트에서 method + path가 맞는 엔드포인트를 찾는다 */
    private Optional<JsonNode> findEndpoint(Long projectId, String method, String endpoint) {
        String specJson = pipelineService.getLatestArtifactsByProject(projectId).stream()
                .filter(a -> a.getArtifactType() == PipelineArtifact.ArtifactType.API_SPEC)
                .map(PipelineArtifact::getContent)
                .findFirst()
                .orElse(null);

        if (specJson == null || specJson.isBlank()) {
            log.debug("API_SPEC 아티팩트 없음 | projectId: {}", projectId);
            return Optional.empty();
        }

        JsonNode endpoints;
        try {
            endpoints = objectMapper.readTree(specJson).path("endpoints");
        } catch (Exception e) {
            log.warn("API_SPEC 파싱 실패 | projectId: {}, error: {}", projectId, e.getMessage());
            return Optional.empty();
        }
        if (!endpoints.isArray()) return Optional.empty();

        JsonNode exactMatch = null;
        JsonNode templateMatch = null;

        for (JsonNode ep : endpoints) {
            if (!method.equalsIgnoreCase(ep.path("method").asText())) continue;

            String specPath = ep.path("path").asText("");
            if (specPath.isBlank()) continue;

            if (specPath.equals(endpoint)) {
                exactMatch = ep;          // 정확히 일치하면 최우선
                break;
            }
            if (templateMatch == null && matchesTemplate(specPath, endpoint)) {
                templateMatch = ep;       // {id} 같은 변수를 포함한 매칭은 후순위
            }
        }

        return Optional.ofNullable(exactMatch != null ? exactMatch : templateMatch);
    }

    /** /api/v1/users/{id} 형태의 템플릿이 실제 경로와 맞는지 검사 */
    private boolean matchesTemplate(String specPath, String requestPath) {
        if (!specPath.contains("{")) return false;

        StringBuilder regex = new StringBuilder("^");
        Matcher m = PATH_VARIABLE.matcher(specPath);
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(specPath.substring(last, m.start())));
            regex.append("[^/]+");
            last = m.end();
        }
        regex.append(Pattern.quote(specPath.substring(last))).append("$");

        return requestPath.matches(regex.toString());
    }

    /**
     * successResponse 스키마로 가짜 응답 본문을 만든다.
     *
     * 명세의 값은 "integer // 사용자 ID" 처럼 타입과 주석이 섞여 있어 타입만 추출해 쓴다.
     * 중첩 객체/배열은 재귀적으로 처리한다.
     */
    private Map<String, Object> buildResponseBody(JsonNode schema) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (schema == null || schema.isMissingNode() || schema.isNull()) return body;

        // 명세가 문자열 하나로 뭉개져 있는 경우
        if (!schema.isObject()) {
            body.put("result", sampleValue("result", schema.asText("string")));
            return body;
        }

        for (Map.Entry<String, JsonNode> entry : schema.properties()) {
            String name = entry.getKey();
            JsonNode value = entry.getValue();

            if (value.isObject()) {
                body.put(name, buildResponseBody(value));
            } else if (value.isArray()) {
                List<Object> arr = new ArrayList<>();
                if (!value.isEmpty()) {
                    JsonNode first = value.get(0);
                    arr.add(first.isObject() ? buildResponseBody(first)
                                             : sampleValue(name, first.asText("string")));
                }
                body.put(name, arr);
            } else {
                body.put(name, sampleValue(name, value.asText("string")));
            }
        }

        return body;
    }

    /**
     * 필드명과 타입으로 그럴듯한 샘플 값을 만든다.
     * 타입이 우선이고, string인 경우 필드명으로 값을 좀 더 현실적으로 고른다.
     */
    private Object sampleValue(String fieldName, String rawType) {
        String type = TYPE_COMMENT.matcher(rawType == null ? "" : rawType).replaceAll("").trim().toLowerCase();
        String name = fieldName == null ? "" : fieldName.toLowerCase();

        if (type.startsWith("bool")) return true;
        if (type.startsWith("int") || type.startsWith("long")) return name.endsWith("id") ? 1 : 123;
        if (type.startsWith("number") || type.startsWith("float") || type.startsWith("double")) return 1234.56;
        if (type.startsWith("array") || type.startsWith("list")) return List.of();
        if (type.startsWith("object") || type.startsWith("map")) return Map.of();

        // 문자열 계열 — 필드명으로 현실적인 값 선택
        if (name.contains("email")) return "sample@timiroom.dev";
        if (name.contains("password")) return "********";
        if (name.contains("phone") || name.contains("tel")) return "010-1234-5678";
        if (name.contains("url") || name.contains("link") || name.contains("image")) return "https://cdn.timiroom.dev/sample.png";
        if (name.contains("token")) return "mock.jwt.token";
        if (name.contains("date") || name.contains("time") || name.endsWith("at")) return "2026-01-01T00:00:00";
        if (name.contains("status")) return "ACTIVE";
        if (name.contains("uuid")) return "00000000-0000-0000-0000-000000000000";
        if (name.contains("name") || name.contains("title")) return "샘플 " + fieldName;

        if (type.startsWith("date") || type.startsWith("timestamp")) return "2026-01-01T00:00:00";
        if (type.startsWith("uuid")) return "00000000-0000-0000-0000-000000000000";

        return "sample_" + fieldName;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 명세에 정의된 엔드포인트 목록을 요약해 돌려준다 (Mock 서버 안내용) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMockEndpoints(Long projectId) {
        String specJson = pipelineService.getLatestArtifactsByProject(projectId).stream()
                .filter(a -> a.getArtifactType() == PipelineArtifact.ArtifactType.API_SPEC)
                .map(PipelineArtifact::getContent)
                .findFirst()
                .orElse(null);

        if (specJson == null || specJson.isBlank()) return List.of();

        try {
            JsonNode endpoints = objectMapper.readTree(specJson).path("endpoints");
            if (!endpoints.isArray()) return List.of();

            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode ep : endpoints) {
                result.add(objectMapper.convertValue(
                        Map.of(
                                "method", ep.path("method").asText(""),
                                "path", ep.path("path").asText(""),
                                "description", ep.path("description").asText(""),
                                "authRequired", ep.path("authRequired").asBoolean(false)
                        ),
                        new TypeReference<Map<String, Object>>() {}));
            }
            return result;
        } catch (Exception e) {
            log.warn("Mock 엔드포인트 목록 조회 실패 | projectId: {}", projectId, e);
            return List.of();
        }
    }
}
