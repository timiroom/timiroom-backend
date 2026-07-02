package com.timiroom.domain.pipeline;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/pipeline")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;

    /**
     * 파이프라인 시작
     * POST /api/v1/pipeline/start/{requirementId}
     * Content-Type: multipart/form-data
     *   - files[]: PDF 파일 (선택, 최대 5개)
     * 응답: { executionId: Long, pipelineId: String(UUID) }
     */
    @PostMapping(value = "/start/{requirementId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> start(
            HttpSession session,
            @PathVariable Long requirementId,
            @RequestPart(value = "files", required = false) List<MultipartFile> files
    ) throws Exception {
        Long memberId = (Long) session.getAttribute("memberId");
        return ResponseEntity.ok(pipelineService.startPipeline(memberId, requirementId, files));
    }

    /**
     * SSE 진행 구독
     * GET /api/v1/pipeline/progress/{pipelineId}
     * event: progress | complete | error
     */
    @GetMapping(value = "/progress/{pipelineId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter progress(@PathVariable String pipelineId) {
        return pipelineService.subscribeProgress(pipelineId);
    }

    /**
     * 내 파이프라인 실행 목록
     * GET /api/v1/pipeline/executions
     */
    @GetMapping("/executions")
    public ResponseEntity<List<PipelineExecution>> myExecutions(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        return ResponseEntity.ok(pipelineService.getExecutionsByMember(memberId));
    }

    /**
     * 실행 결과 Artifact 조회 (PRD, DB_SCHEMA, API_SPEC, FEATURE_LIST, MARKET_RESEARCH)
     * GET /api/v1/pipeline/executions/{executionId}/artifacts
     */
    @GetMapping("/executions/{executionId}/artifacts")
    public ResponseEntity<List<PipelineArtifact>> artifacts(@PathVariable Long executionId) {
        return ResponseEntity.ok(pipelineService.getArtifactsByExecution(executionId));
    }

    /**
     * 프로젝트의 최신 완료된 파이프라인 Artifact 조회
     * GET /api/v1/pipeline/projects/{projectId}/artifacts
     */
    @GetMapping("/projects/{projectId}/artifacts")
    public ResponseEntity<List<PipelineArtifact>> artifactsByProject(@PathVariable Long projectId) {
        return ResponseEntity.ok(pipelineService.getLatestArtifactsByProject(projectId));
    }

    /**
     * Artifact 내용 수정
     * PATCH /api/v1/pipeline/artifacts/{artifactId}
     * Body: { "content": "..." }
     */
    @PatchMapping("/artifacts/{artifactId}")
    public ResponseEntity<Void> updateArtifact(
            @PathVariable Long artifactId,
            @RequestBody Map<String, String> body
    ) {
        pipelineService.updateArtifact(artifactId, body.get("content"));
        return ResponseEntity.ok().build();
    }
}
