package com.timiroom.domain.github;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/github")
public class GithubInstallationController {

    private final GithubInstallationService githubInstallationService;

    /** 저장된 설치 목록 — GET /api/v1/github/installations */
    @GetMapping("/installations")
    public ResponseEntity<?> getInstallations(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));

        return ResponseEntity.ok(githubInstallationService.getAll().stream().map(this::toMap).toList());
    }

    /** GitHub에서 설치 목록 동기화 — POST /api/v1/github/installations/sync */
    @PostMapping("/installations/sync")
    public ResponseEntity<?> syncInstallations(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));

        try {
            return ResponseEntity.ok(githubInstallationService.syncInstallations().stream().map(this::toMap).toList());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /** 설치가 접근 가능한 레포 목록 — GET /api/v1/github/installations/{installationId}/repos */
    @GetMapping("/installations/{installationId}/repos")
    public ResponseEntity<?> getRepositories(HttpSession session, @PathVariable Long installationId) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));

        try {
            return ResponseEntity.ok(githubInstallationService.getRepositories(installationId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> toMap(GithubInstallation installation) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("id", installation.getId());
        map.put("installationId", installation.getInstallationId());
        map.put("accountLogin", installation.getAccountLogin());
        map.put("accountType", installation.getAccountType());
        map.put("teamId", installation.getTeamId());
        return map;
    }
}
