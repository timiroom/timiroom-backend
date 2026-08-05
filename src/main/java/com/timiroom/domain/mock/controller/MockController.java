package com.timiroom.domain.mock.controller;

import com.timiroom.domain.mock.entity.MockApiLog;
import com.timiroom.domain.mock.service.MockService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;

/**
 * Mock 서버 엔드포인트
 *
 * 파이프라인이 만든 API 명세를 실제로 호출 가능한 가상 API로 노출한다.
 * 프론트엔드는 백엔드 구현 완료를 기다리지 않고 이 URL로 개발을 시작할 수 있다.
 *
 *   호출:  {METHOD} /mock/{projectId}/api/v1/users/3
 *   목록:  GET      /mock/{projectId}/_endpoints
 *   로그:  GET      /api/v1/mock/{projectId}/logs   (인증 필요)
 *
 * 지원 헤더:
 *   X-Mock-Delay : 지정한 밀리초만큼 응답을 지연 (네트워크 지연 시뮬레이션)
 *   X-Mock-Error : 지정한 상태 코드로 강제 에러 응답 (에러 처리 테스트)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MockController {

    /** 의도치 않은 장시간 블로킹을 막기 위한 지연 상한 */
    private static final long MAX_MOCK_DELAY_MS = 10_000L;

    private final MockService mockService;

    /** 이 프로젝트에서 호출 가능한 Mock 엔드포인트 목록 */
    @GetMapping("/mock/{projectId}/_endpoints")
    public ResponseEntity<Map<String, Object>> endpoints(@PathVariable Long projectId) {
        List<Map<String, Object>> list = mockService.listMockEndpoints(projectId);
        return ResponseEntity.ok(Map.of(
                "projectId", projectId,
                "baseUrl", "/mock/" + projectId,
                "count", list.size(),
                "endpoints", list
        ));
    }

    /** Mock 요청 처리 — 모든 메서드/경로를 받는다 */
    @RequestMapping("/mock/{projectId}/**")
    public ResponseEntity<Map<String, Object>> handle(@PathVariable Long projectId,
                                                     HttpServletRequest request) {
        long startedAt = System.currentTimeMillis();
        String method = request.getMethod();
        String endpoint = extractEndpoint(request, projectId);

        Integer forcedStatus = parseIntHeader(request.getHeader("X-Mock-Error"));
        MockService.MockResult result = mockService.handle(projectId, method, endpoint, forcedStatus);

        applyDelay(request.getHeader("X-Mock-Delay"));

        long latency = System.currentTimeMillis() - startedAt;
        mockService.log(projectId, method, endpoint, result.statusCode(), latency, result.matchedPath());

        log.debug("Mock 요청 | {} {} → {} ({}ms, 매칭: {})",
                method, endpoint, result.statusCode(), latency, result.matchedPath());

        return ResponseEntity.status(result.statusCode())
                .header("X-Mock-Server", "timiroom")
                .header("X-Mock-Matched-Path", result.matchedPath() == null ? "none" : result.matchedPath())
                .body(result.body());
    }

    /** Mock 호출 로그 조회 (팀원이 대시보드에서 확인) */
    @GetMapping("/api/v1/mock/{projectId}/logs")
    public ResponseEntity<List<MockApiLog>> logs(@PathVariable Long projectId,
                                                 @RequestParam(defaultValue = "50") int limit) {
        int capped = Math.min(Math.max(limit, 1), 200);
        return ResponseEntity.ok(mockService.getLogs(projectId, PageRequest.of(0, capped)));
    }

    /**
     * 요청 URI에서 /mock/{projectId} 프리픽스를 떼어 실제 명세 경로만 남긴다.
     * 예) /mock/7/api/v1/users/3 → /api/v1/users/3
     */
    private String extractEndpoint(HttpServletRequest request, Long projectId) {
        String fullPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        if (fullPath == null || fullPath.isBlank()) {
            fullPath = request.getRequestURI();
        }

        String prefix = "/mock/" + projectId;
        String endpoint = fullPath.startsWith(prefix) ? fullPath.substring(prefix.length()) : fullPath;
        return endpoint.isBlank() ? "/" : endpoint;
    }

    private Integer parseIntHeader(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int value = Integer.parseInt(raw.trim());
            return (value >= 100 && value <= 599) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void applyDelay(String rawDelay) {
        Integer requested = parseNonNegativeInt(rawDelay);
        if (requested == null || requested == 0) return;

        long delay = Math.min(requested, MAX_MOCK_DELAY_MS);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Integer parseNonNegativeInt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
