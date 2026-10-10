package com.timiroom.domain.github;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/teams/{teamId}/github")
public class GithubInstallationController {

    private final GithubInstallationService githubInstallationService;

    /** 이 워크스페이스에 연결된 설치 목록 — GET /api/v1/teams/{teamId}/github/installations */
    @GetMapping("/installations")
    public ResponseEntity<?> getInstallations(HttpSession session, @PathVariable Long teamId) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        try {
            return ResponseEntity.ok(githubInstallationService.getByTeam(teamId, memberId).stream().map(this::toMap).toList());
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GitHub에서 설치 목록 동기화 — POST /api/v1/teams/{teamId}/github/installations/sync
     * 전역으로 동기화한 뒤, 이 팀에 연결된 것과 아직 어느 팀에도 연결되지 않은 후보를 함께 반환한다.
     */
    @PostMapping("/installations/sync")
    public ResponseEntity<?> syncInstallations(HttpSession session, @PathVariable Long teamId) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        try {
            githubInstallationService.syncInstallations(teamId, memberId);
            Map<String, Object> result = new HashMap<>();
            result.put("connected", githubInstallationService.getByTeam(teamId, memberId).stream().map(this::toMap).toList());
            result.put("unassigned", githubInstallationService.getUnassigned(teamId, memberId).stream().map(this::toMap).toList());
            return ResponseEntity.ok(result);
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /** 아직 어느 팀에도 연결되지 않은 설치 후보 — GET /api/v1/teams/{teamId}/github/installations/unassigned */
    @GetMapping("/installations/unassigned")
    public ResponseEntity<?> getUnassigned(HttpSession session, @PathVariable Long teamId) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        try {
            return ResponseEntity.ok(githubInstallationService.getUnassigned(teamId, memberId).stream().map(this::toMap).toList());
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    /** 미할당 설치를 이 워크스페이스에 연결 — POST /api/v1/teams/{teamId}/github/installations/{installationId}/link */
    @PostMapping("/installations/{installationId}/link")
    public ResponseEntity<?> link(HttpSession session, @PathVariable Long teamId, @PathVariable Long installationId) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        try {
            return ResponseEntity.ok(toMap(githubInstallationService.linkToTeam(teamId, memberId, installationId)));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** 워크스페이스 연결 해제 — DELETE /api/v1/teams/{teamId}/github/installations/{installationId} */
    @DeleteMapping("/installations/{installationId}")
    public ResponseEntity<?> unlink(HttpSession session, @PathVariable Long teamId, @PathVariable Long installationId) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        try {
            githubInstallationService.unlinkFromTeam(teamId, memberId, installationId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /** 설치가 접근 가능한 레포 목록 — GET /api/v1/teams/{teamId}/github/installations/{installationId}/repos */
    @GetMapping("/installations/{installationId}/repos")
    public ResponseEntity<?> getRepositories(HttpSession session, @PathVariable Long teamId, @PathVariable Long installationId) {
        Long memberId = memberId(session);
        if (memberId == null) return unauthorized();
        try {
            return ResponseEntity.ok(githubInstallationService.getRepositories(teamId, memberId, installationId));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    private Long memberId(HttpSession session) { return (Long) session.getAttribute("memberId"); }
    private ResponseEntity<?> unauthorized() { return ResponseEntity.status(401).body(Map.of("error", "Not logged in")); }

    private Map<String, Object> toMap(GithubInstallation installation) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("id", installation.getId());
        map.put("installationId", installation.getInstallationId());
        map.put("accountLogin", installation.getAccountLogin());
        map.put("accountType", installation.getAccountType());
        map.put("teamId", installation.getTeamId());
        return map;
    }

    private Map<String, Object> toMap(com.timiroom.infra.github.dto.GithubInstallationInfo info) {
        HashMap<String, Object> map = new HashMap<>();
        map.put("installationId", info.installationId());
        map.put("accountLogin", info.accountLogin());
        map.put("accountType", info.accountType());
        return map;
    }
}
