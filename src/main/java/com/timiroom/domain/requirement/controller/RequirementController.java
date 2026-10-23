package com.timiroom.domain.requirement.controller;

import com.timiroom.domain.requirement.enums.RequirementStatus;
import com.timiroom.domain.requirement.service.RequirementService;
import com.timiroom.domain.requirement.entity.Requirement;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/requirements")
@RequiredArgsConstructor
public class RequirementController {

    private final RequirementService requirementService;

    /**
     * 요구사항(폼) 저장 — 프로젝트 PM만
     * body: { projectId, title, content(FormData JSON) }
     */
    @PostMapping
    public ResponseEntity<Requirement> create(HttpSession session,
                                              @RequestBody Map<String, Object> body) {
        Long memberId = memberId(session);
        Object projectId = body.get("projectId");
        Object title = body.get("title");
        Object content = body.get("content");
        if (projectId == null || title == null || content == null) {
            throw new IllegalArgumentException("projectId, title, content가 필요합니다");
        }
        return ResponseEntity.ok(requirementService.create(
                Long.valueOf(projectId.toString()), memberId, title.toString(), content.toString()));
    }

    /** 요구사항 단건 조회 */
    @GetMapping("/{requirementId}")
    public ResponseEntity<Requirement> getOne(HttpSession session, @PathVariable Long requirementId) {
        return ResponseEntity.ok(requirementService.getById(requirementId, memberId(session)));
    }

    /** 프로젝트별 요구사항 목록 */
    @GetMapping("/project/{projectId}")
    public ResponseEntity<List<Requirement>> byProject(HttpSession session, @PathVariable Long projectId) {
        return ResponseEntity.ok(requirementService.getByProject(projectId, memberId(session)));
    }

    /** 내 요구사항 목록 */
    @GetMapping
    public ResponseEntity<List<Requirement>> myRequirements(HttpSession session) {
        return ResponseEntity.ok(requirementService.getMyRequirements(memberId(session)));
    }

    @PatchMapping("/{requirementId}/status")
    public ResponseEntity<Void> updateStatus(
            HttpSession session,
            @PathVariable Long requirementId,
            @RequestBody RequirementStatus status
    ) {
        requirementService.updateStatus(requirementId, memberId(session), status);
        return ResponseEntity.ok().build();
    }

    private Long memberId(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return memberId;
    }
}
