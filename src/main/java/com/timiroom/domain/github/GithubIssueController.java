package com.timiroom.domain.github;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId}/issues")
public class GithubIssueController {

    private final GithubIssueService githubIssueService;

    @GetMapping
    public ResponseEntity<?> list(HttpSession session, @PathVariable Long projectId) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        try {
            return ResponseEntity.ok(githubIssueService.list(projectId, memberId));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> create(HttpSession session, @PathVariable Long projectId,
                                    @RequestBody Map<String, Object> body) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        Long repoId = asLong(body.get("repoId"));
        String title = asString(body.get("title"));
        if (repoId == null || title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "repoId와 title은 필수입니다"));
        }
        try {
            return ResponseEntity.status(201).body(githubIssueService.create(projectId, memberId, repoId,
                    title, asString(body.get("body")), labels(body.get("labels")),
                    asLong(body.get("ownerMemberId"))));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{repoId}/{issueNumber}")
    public ResponseEntity<?> update(HttpSession session, @PathVariable Long projectId,
                                    @PathVariable Long repoId, @PathVariable int issueNumber,
                                    @RequestBody Map<String, Object> body) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        try {
            return ResponseEntity.ok(githubIssueService.update(projectId, memberId, repoId, issueNumber,
                    asString(body.get("title")), asString(body.get("body")),
                    body.containsKey("labels") ? labels(body.get("labels")) : null,
                    asLong(body.get("ownerMemberId"))));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    private Long memberId(HttpSession session) { return (Long) session.getAttribute("memberId"); }
    private ResponseEntity<?> unauthorized() { return ResponseEntity.status(401).body(Map.of("error", "Not logged in")); }
    private String asString(Object value) { return value == null ? null : value.toString(); }
    private Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.parseLong(value.toString()); } catch (NumberFormatException ignored) { return null; }
    }
    private List<String> labels(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        return collection.stream().filter(java.util.Objects::nonNull).map(Object::toString)
                .map(String::trim).filter(label -> !label.isBlank()).distinct().toList();
    }
}
