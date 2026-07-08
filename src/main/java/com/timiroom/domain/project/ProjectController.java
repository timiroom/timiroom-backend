package com.timiroom.domain.project;

import com.timiroom.domain.pipeline.PipelineArtifact;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
    public ResponseEntity<?> create(HttpSession session,
                                    @RequestBody Map<String, Object> body) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            String teamIdValue = body.get("teamId") == null ? null : body.get("teamId").toString();
            String projectName = body.get("projectName") == null ? null : body.get("projectName").toString();
            if (teamIdValue == null || teamIdValue.isBlank()) {
                return badRequest("teamId가 필요합니다");
            }
            if (projectName == null || projectName.isBlank()) {
                return badRequest("projectName이 필요합니다");
            }
            Long teamId = Long.valueOf(teamIdValue);
            return ResponseEntity.ok(projectService.create(
                    teamId, memberId,
                    projectName,
                    body.getOrDefault("description", "").toString()));
        } catch (NumberFormatException e) {
            return badRequest("teamId가 올바르지 않습니다");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 내 프로젝트 목록 */
    @GetMapping
    public ResponseEntity<?> myProjects(HttpSession session) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();
        return ResponseEntity.ok(projectService.getMyProjects(memberId));
    }

    /** 팀 내 프로젝트 목록 */
    @GetMapping("/team/{teamId}")
    public ResponseEntity<?> byTeam(HttpSession session,
                                    @PathVariable Long teamId) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            return ResponseEntity.ok(projectService.getByTeam(teamId, memberId));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 프로젝트 단건 조회 */
    @GetMapping("/{projectId}")
    public ResponseEntity<?> getOne(HttpSession session,
                                    @PathVariable Long projectId) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            return ResponseEntity.ok(projectService.getById(projectId, memberId));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 프로젝트 멤버 목록 */
    @GetMapping("/{projectId}/members")
    public ResponseEntity<?> members(HttpSession session,
                                     @PathVariable Long projectId) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            return ResponseEntity.ok(projectService.getMembers(projectId, memberId));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 프로젝트 멤버 추가 */
    @PostMapping("/{projectId}/members")
    public ResponseEntity<?> addMember(HttpSession session,
                                       @PathVariable Long projectId,
                                       @RequestBody Map<String, String> body) {
        Long actorMemberId = getMemberId(session);
        if (actorMemberId == null) return unauthorized();

        try {
            String memberIdValue = body.get("memberId");
            String roleValue = body.get("role");
            if (memberIdValue == null || memberIdValue.isBlank()) {
                return badRequest("memberId가 필요합니다");
            }
            if (roleValue == null || roleValue.isBlank()) {
                return badRequest("role이 필요합니다");
            }
            ProjectRole role = ProjectRole.valueOf(roleValue.toUpperCase());
            return ResponseEntity.ok(projectService.addMember(
                    projectId,
                    actorMemberId,
                    Long.valueOf(memberIdValue),
                    role));
        } catch (NumberFormatException e) {
            return badRequest("memberId가 올바르지 않습니다");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 프로젝트 수정 */
    @PatchMapping("/{projectId}")
    public ResponseEntity<?> update(HttpSession session,
                                    @PathVariable Long projectId,
                                    @RequestBody Map<String, Object> body) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            String name        = body.get("projectName")  != null ? body.get("projectName").toString()  : null;
            String description = body.get("description")  != null ? body.get("description").toString()  : null;
            String status      = body.get("status")       != null ? body.get("status").toString()       : null;
            return ResponseEntity.ok(projectService.updateProject(projectId, memberId, name, description, status));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 프로젝트 멤버 역할 변경 */
    @PatchMapping("/{projectId}/members/{targetMemberId}")
    public ResponseEntity<?> updateMemberRole(HttpSession session,
                                              @PathVariable Long projectId,
                                              @PathVariable Long targetMemberId,
                                              @RequestBody Map<String, String> body) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            String roleValue = body.get("role");
            if (roleValue == null || roleValue.isBlank()) return badRequest("role이 필요합니다");
            ProjectRole role = ProjectRole.valueOf(roleValue.toUpperCase());
            return ResponseEntity.ok(projectService.updateMemberRole(projectId, memberId, targetMemberId, role));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 프로젝트 멤버 제거 */
    @DeleteMapping("/{projectId}/members/{targetMemberId}")
    public ResponseEntity<?> removeMember(HttpSession session,
                                          @PathVariable Long projectId,
                                          @PathVariable Long targetMemberId) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            projectService.removeMember(projectId, memberId, targetMemberId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 프로젝트 삭제 */
    @DeleteMapping("/{projectId}")
    public ResponseEntity<?> delete(HttpSession session,
                                    @PathVariable Long projectId) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            projectService.delete(projectId, memberId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /**
     * 문서 초안 조회 — GET /api/v1/projects/{id}/documents/{type}
     * type: PRD | DB_SCHEMA | API_SPEC | FEATURE_LIST | MARKET_RESEARCH
     */
    @GetMapping("/{projectId}/documents/{type}")
    public ResponseEntity<?> getDocument(HttpSession session,
                                         @PathVariable Long projectId,
                                         @PathVariable String type) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        PipelineArtifact.ArtifactType artifactType;
        try {
            artifactType = PipelineArtifact.ArtifactType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "지원하지 않는 문서 타입: " + type));
        }

        try {
            return projectService.getDocument(projectId, memberId, artifactType)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /**
     * 문서 저장 — PATCH /api/v1/projects/{id}/documents/{type}
     */
    @PatchMapping("/{projectId}/documents/{type}")
    public ResponseEntity<?> saveDocument(HttpSession session,
                                          @PathVariable Long projectId,
                                          @PathVariable String type,
                                          @RequestBody Map<String, String> body) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

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

        try {
            return ResponseEntity.ok(projectService.saveDocument(projectId, memberId, artifactType, content));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    private Long getMemberId(HttpSession session) {
        return (Long) session.getAttribute("memberId");
    }

    private ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Unauthorized"));
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private ResponseEntity<Map<String, String>> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", message));
    }

    private ResponseEntity<Map<String, String>> forbidden(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", message));
    }
}
