package com.timiroom.domain.qa.controller;

import com.timiroom.domain.qa.dto.CreateQaScenarioRequest;
import com.timiroom.domain.qa.dto.GenerateQaScenarioRequest;
import com.timiroom.domain.qa.service.QaScenarioService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * QA 테스트 시나리오 — 생성·조회·실행.
 *
 * POST .../generate 는 AI가 기능 명세를 근거로 시나리오를 만들고,
 * POST .../{id}/run 은 그 시나리오가 현재 명세·코드를 근거로 통과할지 AI가 추론한다.
 * 둘 다 실제 코드를 컴파일·실행하지 않는다.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/qa-scenarios")
@RequiredArgsConstructor
public class QaScenarioController {

    private final QaScenarioService qaScenarioService;

    @GetMapping
    public ResponseEntity<?> list(HttpSession session, @PathVariable Long projectId) {
        return withMember(session, memberId -> ResponseEntity.ok(qaScenarioService.list(projectId, memberId)));
    }

    @PostMapping
    public ResponseEntity<?> create(HttpSession session, @PathVariable Long projectId,
                                     @RequestBody CreateQaScenarioRequest request) {
        return withMember(session, memberId -> ResponseEntity.ok(qaScenarioService.create(projectId, memberId, request)));
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(HttpSession session, @PathVariable Long projectId,
                                       @RequestBody GenerateQaScenarioRequest request) {
        return withMember(session, memberId -> ResponseEntity.ok(qaScenarioService.generate(projectId, memberId, request)));
    }

    @PostMapping("/{scenarioId}/run")
    public ResponseEntity<?> run(HttpSession session, @PathVariable Long projectId, @PathVariable Long scenarioId) {
        return withMember(session, memberId -> ResponseEntity.ok(qaScenarioService.run(projectId, memberId, scenarioId)));
    }

    @DeleteMapping("/{scenarioId}")
    public ResponseEntity<?> delete(HttpSession session, @PathVariable Long projectId, @PathVariable Long scenarioId) {
        return withMember(session, memberId -> {
            qaScenarioService.delete(projectId, memberId, scenarioId);
            return ResponseEntity.noContent().build();
        });
    }

    private ResponseEntity<?> withMember(HttpSession session, java.util.function.Function<Long, ResponseEntity<?>> action) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
        }
        try {
            return action.apply(memberId);
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }
}
