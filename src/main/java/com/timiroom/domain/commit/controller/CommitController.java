package com.timiroom.domain.commit.controller;

import com.timiroom.domain.commit.service.CommitService;
import com.timiroom.domain.commit.entity.Commit;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CommitController {

    private final CommitService commitService;

    /** 내가 참여한 프로젝트들의 커밋 — GET /api/v1/commits */
    @GetMapping("/api/v1/commits")
    public ResponseEntity<List<Commit>> getAll(HttpSession session) {
        return ResponseEntity.ok(commitService.getMine(memberId(session)));
    }

    /** 프로젝트별 커밋 조회 — GET /api/v1/projects/{id}/commits */
    @GetMapping("/api/v1/projects/{projectId}/commits")
    public ResponseEntity<List<Commit>> getByProject(HttpSession session, @PathVariable Long projectId) {
        return ResponseEntity.ok(commitService.getByProject(projectId, memberId(session)));
    }

    /** 커밋 생성 — POST /api/v1/commits */
    @PostMapping("/api/v1/commits")
    public ResponseEntity<Commit> create(
            HttpSession session,
            @RequestBody Map<String, Object> body
    ) {
        Long memberId = memberId(session);
        Object projectId = body.get("projectId");
        Object message = body.get("message");
        if (projectId == null || !(message instanceof String text) || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(commitService.create(Long.valueOf(projectId.toString()), memberId, text));
    }

    private Long memberId(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        return memberId;
    }
}
