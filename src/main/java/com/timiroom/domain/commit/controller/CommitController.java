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

    /** 커밋 전체 조회 — GET /api/v1/commits */
    @GetMapping("/api/v1/commits")
    public ResponseEntity<List<Commit>> getAll() {
        return ResponseEntity.ok(commitService.getAll());
    }

    /** 프로젝트별 커밋 조회 — GET /api/v1/projects/{id}/commits */
    @GetMapping("/api/v1/projects/{projectId}/commits")
    public ResponseEntity<List<Commit>> getByProject(@PathVariable Long projectId) {
        return ResponseEntity.ok(commitService.getByProject(projectId));
    }

    /** 커밋 생성 — POST /api/v1/commits */
    @PostMapping("/api/v1/commits")
    public ResponseEntity<Commit> create(
            HttpSession session,
            @RequestBody Map<String, Object> body
    ) {
        Long memberId = (Long) session.getAttribute("memberId");
        Long projectId = Long.valueOf(body.get("projectId").toString());
        String message = (String) body.get("message");

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(commitService.create(projectId, memberId, message));
    }
}
