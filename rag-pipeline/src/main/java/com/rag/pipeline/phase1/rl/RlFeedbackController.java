package com.rag.pipeline.phase1.rl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Phase1 RL 파라미터 조회 엔드포인트
 *
 * GET /api/rl/params — 현재 최적 threshold 조회
 *
 * 피드백은 Cohere Reranker 점수로 자동화 (수동 엔드포인트 제거)
 */
@Slf4j
@RestController
@RequestMapping("/api/rl")
@RequiredArgsConstructor
public class RlFeedbackController {

    private final SearchRLService rlService;

    @GetMapping("/params")
    public ResponseEntity<Map<String, Object>> getParams() {
        SearchParams p = rlService.getCurrentParams();
        return ResponseEntity.ok(Map.of(
                "vectorWeight",        p.vectorWeight(),
                "keywordWeight",       p.keywordWeight(),
                "similarityThreshold", p.similarityThreshold()
        ));
    }
}
