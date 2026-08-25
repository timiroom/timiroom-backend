package com.timiroom.domain.graph.controller;

import com.timiroom.domain.graph.dto.GraphResponse;
import com.timiroom.domain.graph.service.KnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지식 그래프 조회
 *
 * GET /api/v1/projects/{projectId}/graph
 *   기능 · API · DB 테이블의 연결 관계를 계산해 반환한다.
 *   저장된 그래프를 읽는 것이 아니라 최신 명세에서 매번 계산하므로
 *   문서를 고치면 그래프도 바로 따라온다.
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class KnowledgeGraphController {

    private final KnowledgeGraphService knowledgeGraphService;

    @GetMapping("/{projectId}/graph")
    public ResponseEntity<GraphResponse> graph(@PathVariable Long projectId) {
        return ResponseEntity.ok(knowledgeGraphService.build(projectId));
    }
}
