package com.timiroom.domain.requirement.controller;

import com.timiroom.domain.requirement.enums.RequirementStatus;
import com.timiroom.domain.requirement.service.RequirementService;
import com.timiroom.domain.requirement.entity.Requirement;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/requirements")
@RequiredArgsConstructor
public class RequirementController {

    private final RequirementService requirementService;

    /**
     * 요구사항(폼) 저장
     * body: { projectId, title, content(FormData JSON) }
     */
    @PostMapping
    public ResponseEntity<Requirement> create(HttpSession session,
                                              @RequestBody Map<String, Object> body) {
        Long memberId = (Long) session.getAttribute("memberId");
        Long projectId = Long.valueOf(body.get("projectId").toString());
        return ResponseEntity.ok(requirementService.create(
                projectId, memberId,
                body.get("title").toString(),
                body.get("content").toString()));
    }

    /** 요구사항 단건 조회 */
    @GetMapping("/{requirementId}")
    public ResponseEntity<Requirement> getOne(@PathVariable Long requirementId) {
        return ResponseEntity.ok(requirementService.getById(requirementId));
    }

    /** 프로젝트별 요구사항 목록 */
    @GetMapping("/project/{projectId}")
    public ResponseEntity<List<Requirement>> byProject(@PathVariable Long projectId) {
        return ResponseEntity.ok(requirementService.getByProject(projectId));
    }

    /** 내 요구사항 목록 */
    @GetMapping
    public ResponseEntity<List<Requirement>> myRequirements(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        return ResponseEntity.ok(requirementService.getMyRequirements(memberId));
    }

    @PatchMapping("/{requirementId}/status")
    public ResponseEntity<Void> updateStatus(
            @PathVariable Long requirementId,
            @RequestBody RequirementStatus status
    ) {
        requirementService.updateStatus(requirementId, status);
        return ResponseEntity.ok().build();
    }
}
