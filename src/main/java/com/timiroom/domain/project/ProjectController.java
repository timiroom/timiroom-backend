package com.timiroom.domain.project;

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
}
