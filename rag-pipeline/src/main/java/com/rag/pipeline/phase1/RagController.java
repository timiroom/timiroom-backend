package com.rag.pipeline.phase1;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Phase 1 REST API
 *
 * POST /api/v1/rag/ingest   — 문서 저장 (지식 베이스 구축)
 * POST /api/v1/rag/context  — 쿼리 → 컨텍스트 프롬프트 생성
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
public class RagController {

    private final DocumentIngestionService ingestionService;
    private final RagPipelineService       ragPipelineService;

    /**
     * 문서 저장 API
     *
     * 요청 예시:
     * POST /api/v1/rag/ingest
     * {
     *   "content": "Spring Security는 인증과 인가를 담당합니다...",
     *   "source":  "spring-docs"
     * }
     */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestBody IngestRequest request) {

        String source = (request.source() != null) ? request.source() : "unknown";

        ingestionService.ingest(
                request.content(),
                Map.of("source", source)
        );

        return ResponseEntity.ok(Map.of(
                "status",  "success",
                "source",  source,
                "message", "문서가 성공적으로 저장되었습니다"
        ));
    }


    /**
     * RAG 컨텍스트 생성 API
     *
     * 요청 예시:
     * POST /api/v1/rag/context
     * {
     *   "query": "로그인 기능 만들어줘"
     * }
     */
    @PostMapping("/context")
    public ResponseEntity<Map<String, Object>> buildContext(
            @RequestBody ContextRequest request) {

        String prompt = ragPipelineService.buildContext(request.query());

        return ResponseEntity.ok(Map.of(
                "query",  request.query(),
                "prompt", prompt
        ));
    }

    // ── Request 객체 ──────────────────────────────────────────────

    record IngestRequest(String content, String source) {}
    record ContextRequest(String query) {}
}
