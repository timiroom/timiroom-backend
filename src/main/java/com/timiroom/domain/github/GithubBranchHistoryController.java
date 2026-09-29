package com.timiroom.domain.github;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId}/repos/{repoId}")
public class GithubBranchHistoryController {

    private final GithubBranchHistoryService githubBranchHistoryService;

    /** 연결 레포의 브랜치 목록 — 읽기 전용. */
    @GetMapping("/branches")
    public ResponseEntity<?> getBranches(HttpSession session, @PathVariable Long projectId, @PathVariable Long repoId) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        try {
            return ResponseEntity.ok(githubBranchHistoryService.getBranches(projectId, memberId, repoId));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /** 연결 레포의 특정 브랜치 커밋 히스토리 — 읽기 전용. */
    @GetMapping("/commits")
    public ResponseEntity<?> getCommits(HttpSession session, @PathVariable Long projectId, @PathVariable Long repoId,
                                        @RequestParam String branch) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        try {
            return ResponseEntity.ok(githubBranchHistoryService.getCommits(projectId, memberId, repoId, branch));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    private Long memberId(HttpSession session) {
        return (Long) session.getAttribute("memberId");
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
    }
}
