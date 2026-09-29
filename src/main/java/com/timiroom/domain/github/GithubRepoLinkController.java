package com.timiroom.domain.github;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId}/repos")
public class GithubRepoLinkController {

    private final GithubRepoLinkService githubRepoLinkService;

    /** 프로젝트에 연결된 레포 목록 — GET /api/v1/projects/{projectId}/repos */
    @GetMapping
    public ResponseEntity<?> getRepos(HttpSession session, @PathVariable Long projectId) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        try {
            return ResponseEntity.ok(githubRepoLinkService.getLinks(projectId, memberId));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** 레포 연결 — POST /api/v1/projects/{projectId}/repos  { installationId, githubRepoId, roleHint? } */
    @PostMapping
    public ResponseEntity<?> linkRepo(HttpSession session, @PathVariable Long projectId,
                                      @RequestBody Map<String, Object> body) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();

        Long installationId = asLong(body.get("installationId"));
        Long githubRepoId = asLong(body.get("githubRepoId"));
        String roleHint = body.get("roleHint") == null ? null : body.get("roleHint").toString();
        if (installationId == null || githubRepoId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "installationId와 githubRepoId는 필수입니다"));
        }

        try {
            return ResponseEntity.ok(githubRepoLinkService.link(projectId, memberId, installationId, githubRepoId, roleHint));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
    }

    /** 레포 연결 해제 — DELETE /api/v1/projects/{projectId}/repos/{repoId} */
    @DeleteMapping("/{repoId}")
    public ResponseEntity<?> unlinkRepo(HttpSession session, @PathVariable Long projectId, @PathVariable Long repoId) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        try {
            githubRepoLinkService.unlink(projectId, memberId, repoId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    private Long memberId(HttpSession session) {
        return (Long) session.getAttribute("memberId");
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
    }

    private Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
