package com.timiroom.domain.github;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId}/pulls")
public class PullRequestController {

    private final PullRequestConsistencyService pullRequestConsistencyService;

    @GetMapping
    public ResponseEntity<?> list(HttpSession session, @PathVariable Long projectId) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        try {
            return ResponseEntity.ok(pullRequestConsistencyService.list(projectId, memberId));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /** 프로젝트에서 가장 최근에 검사된 PR의 정합성 요약 — 명세 패널 배지용. 검사 이력이 없으면 null. */
    @GetMapping("/consistency/latest")
    public ResponseEntity<?> latestConsistency(HttpSession session, @PathVariable Long projectId) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        try {
            var summary = pullRequestConsistencyService.getLatestSummary(projectId, memberId);
            return summary == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(summary);
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
    }

    /** 검사 결과를 GitHub PR review COMMENT로 게시한다. */
    @PostMapping("/{repoId}/{number}/check")
    public ResponseEntity<?> check(HttpSession session, @PathVariable Long projectId,
                                   @PathVariable Long repoId, @PathVariable int number) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        try {
            return ResponseEntity.ok(pullRequestConsistencyService.checkAndReview(projectId, memberId, repoId, number));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    private Long memberId(HttpSession session) { return (Long) session.getAttribute("memberId"); }
    private ResponseEntity<?> unauthorized() { return ResponseEntity.status(401).body(Map.of("error", "Not logged in")); }
}
