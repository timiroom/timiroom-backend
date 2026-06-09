package com.timiroom.domain.project;

import com.timiroom.domain.pipeline.PipelineArtifact;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    /** 프로젝트 생성 */
    @PostMapping
    public ResponseEntity<Project> create(HttpSession session,
                                          @RequestBody Map<String, Object> body) {
        Long memberId = (Long) session.getAttribute("memberId");
        Long teamId = Long.valueOf(body.get("teamId").toString());
        return ResponseEntity.ok(projectService.create(
                teamId, memberId,
                body.get("projectName").toString(),
                body.getOrDefault("description", "").toString()));
    }

    /** 내 프로젝트 목록 */
    @GetMapping
    public ResponseEntity<List<Project>> myProjects(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        return ResponseEntity.ok(projectService.getMyProjects(memberId));
    }

    /** 팀 내 프로젝트 목록 */
    @GetMapping("/team/{teamId}")
    public ResponseEntity<List<Project>> byTeam(@PathVariable Long teamId) {
        return ResponseEntity.ok(projectService.getByTeam(teamId));
    }

    /** 프로젝트 단건 조회 */
    @GetMapping("/{projectId}")
    public ResponseEntity<Project> getOne(@PathVariable Long projectId) {
        return ResponseEntity.ok(projectService.getById(projectId));
    }

    /** 프로젝트 멤버 목록 */
    @GetMapping("/{projectId}/members")
    public ResponseEntity<List<ProjectMember>> members(@PathVariable Long projectId) {
        return ResponseEntity.ok(projectService.getMembers(projectId));
    }

    /** 프로젝트 멤버 추가 */
    @PostMapping("/{projectId}/members")
    public ResponseEntity<ProjectMember> addMember(@PathVariable Long projectId,
                                                   @RequestBody Map<String, String> body) {
        ProjectRole role = ProjectRole.valueOf(body.get("role").toUpperCase());
        return ResponseEntity.ok(projectService.addMember(
                projectId, Long.valueOf(body.get("memberId")), role));
    }

    /** 프로젝트 삭제 */
    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> delete(HttpSession session,
                                       @PathVariable Long projectId) {
        Long memberId = (Long) session.getAttribute("memberId");
        projectService.delete(projectId, memberId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 문서 초안 조회 — GET /api/v1/projects/{id}/documents/{type}
     * type: PRD | DB_SCHEMA | API_SPEC | FEATURE_LIST | MARKET_RESEARCH
     */
    @GetMapping("/{projectId}/documents/{type}")
    public ResponseEntity<?> getDocument(
            @PathVariable Long projectId,
            @PathVariable String type
    ) {
        PipelineArtifact.ArtifactType artifactType;
        try {
            artifactType = PipelineArtifact.ArtifactType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "지원하지 않는 문서 타입: " + type));
        }
        return projectService.getDocument(projectId, artifactType)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 문서 저장 — PATCH /api/v1/projects/{id}/documents/{type}
     */
    @PatchMapping("/{projectId}/documents/{type}")
    public ResponseEntity<?> saveDocument(
            @PathVariable Long projectId,
            @PathVariable String type,
            @RequestBody Map<String, String> body
    ) {
        PipelineArtifact.ArtifactType artifactType;
        try {
            artifactType = PipelineArtifact.ArtifactType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "지원하지 않는 문서 타입: " + type));
        }
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "content가 없습니다"));
        }
        return ResponseEntity.ok(projectService.saveDocument(projectId, artifactType, content));
    }
}
