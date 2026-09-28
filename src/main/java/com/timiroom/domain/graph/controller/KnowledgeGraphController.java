package com.timiroom.domain.graph.controller;

import com.timiroom.domain.graph.service.KnowledgeGraphService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 지식 그래프 조회
 *
 * GET /api/v1/projects/{projectId}/graph
 *   기능 · API · DB 테이블의 연결 관계를 계산해 반환한다.
 *   저장된 그래프를 읽는 것이 아니라 최신 명세에서 매번 계산하므로
 *   문서를 고치면 그래프도 바로 따라온다.
 *
 * 응답에는 기능 목록·API 경로와 설명·테이블 컬럼·PR 제목이 모두 담긴다.
 * 프로젝트 명세를 통째로 내려주는 것과 같으므로, 소속을 확인하지 않으면
 * 로그인한 누구나 projectId만 바꿔 남의 프로젝트를 읽을 수 있다.
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class KnowledgeGraphController {

    private final KnowledgeGraphService knowledgeGraphService;

    @GetMapping("/{projectId}/graph")
    public ResponseEntity<?> graph(HttpSession session, @PathVariable Long projectId) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
        }
        try {
            return ResponseEntity.ok(knowledgeGraphService.build(projectId, memberId));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
    }
}
