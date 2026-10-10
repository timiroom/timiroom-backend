package com.rag.pipeline.phase1.rl;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * Phase1 similarityThreshold 자동 튜닝 — Epsilon-Greedy + Gaussian + EMA
 *
 * - vectorWeight / keywordWeight : 1.0 고정
 * - similarityThreshold          : Gaussian 탐색으로 소수점 4자리 정밀도 튜닝
 * - 보상 신호                     : Cohere Reranker 평균 관련도 점수 (0.0~1.0)
 * - Baseline                     : 최근 20회 Cohere 점수 슬라이딩 윈도우 평균
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchRLService {

    private final JdbcTemplate jdbcTemplate;

    private static final double ALPHA           = 0.1;
    private static final double EPSILON         = 0.15;
    private static final int    BASELINE_WINDOW = 20;
    private static final double EXPLORE_SIGMA   = 0.05;
    private static final double THRESHOLD_MIN   = 0.1;
    private static final double THRESHOLD_MAX   = 0.6;

    private volatile double currentThreshold = 0.3;
    private final ArrayDeque<Double> baselineWindow = new ArrayDeque<>(BASELINE_WINDOW);
    private final Random random = new Random();

    @PostConstruct
    public void init() {
        try {
            Double loaded = jdbcTemplate.queryForObject(
                    "SELECT similarity_threshold FROM rl_params WHERE id = 1",
                    Double.class
            );
            if (loaded != null) {
                currentThreshold = loaded;
                log.info("Phase1 RL 파라미터 로드 — threshold:{}", String.format("%.4f", currentThreshold));
            }
        } catch (Exception e) {
            log.warn("Phase1 RL 파라미터 로드 실패 — 기본값 사용: {}", e.getMessage());
        }
    }

    /**
     * epsilon-greedy: 15% Gaussian 탐색, 85% 현재 최적값
     * vectorWeight / keywordWeight 는 항상 1.0 고정
     */
    public SearchParams getParams() {
        double threshold;
        if (random.nextDouble() < EPSILON) {
            double noise = random.nextGaussian() * EXPLORE_SIGMA;
            threshold = clamp(
                    Math.round((currentThreshold + noise) * 10000.0) / 10000.0,
                    THRESHOLD_MIN, THRESHOLD_MAX
            );
            log.debug("Phase1 RL 탐색 — base={} noise={} → explored={}",
                    String.format("%.4f", currentThreshold),
                    String.format("%.4f", noise),
                    String.format("%.4f", threshold));
        } else {
            threshold = currentThreshold;
        }
        return new SearchParams(1.0, 1.0, threshold);
    }

    /** 검색 실행 시 사용된 threshold 기록 */
    public void logSearch(String pipelineId, SearchParams params, int chunkCount) {
        if (pipelineId == null) return;
        try {
            jdbcTemplate.update("""
                    INSERT INTO rl_search_log
                        (pipeline_id, vector_weight, keyword_weight, similarity_threshold, chunk_count)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (pipeline_id) DO UPDATE
                        SET similarity_threshold = EXCLUDED.similarity_threshold,
                            chunk_count          = EXCLUDED.chunk_count
                    """,
                    pipelineId,
                    params.vectorWeight(),
                    params.keywordWeight(),
                    params.similarityThreshold(),
                    chunkCount
            );
        } catch (Exception e) {
            log.warn("Phase1 RL 로그 저장 실패 — pipelineId: {}, 원인: {}", pipelineId, e.getMessage());
        }
    }

    /**
     * Cohere Reranker 평균 점수로 threshold 업데이트
     *
     * advantage = avgCohereScore - baseline
     * new_threshold = current + sign(advantage) × α × |advantage| × (used - current)
     */
    public void applyRerankScore(String pipelineId, double avgCohereScore) {
        if (pipelineId == null) return;

        synchronized (baselineWindow) {
            if (baselineWindow.size() >= BASELINE_WINDOW) baselineWindow.pollFirst();
            baselineWindow.addLast(avgCohereScore);
        }

        double baseline;
        synchronized (baselineWindow) {
            baseline = baselineWindow.stream().mapToDouble(d -> d).average().orElse(0.5);
        }
        double advantage = avgCohereScore - baseline;

        log.info("Phase1 RL 피드백 — score:{} baseline:{} advantage:{}",
                String.format("%.3f", avgCohereScore),
                String.format("%.3f", baseline),
                String.format("%.3f", advantage));

        try {
            Double usedThreshold = jdbcTemplate.queryForObject(
                    "SELECT similarity_threshold FROM rl_search_log WHERE pipeline_id = ?",
                    Double.class, pipelineId
            );

            if (usedThreshold == null) {
                log.warn("Phase1 RL 로그 없음 — 업데이트 스킵: {}", pipelineId);
                return;
            }

            jdbcTemplate.update(
                    "UPDATE rl_search_log SET avg_cohere_score = ? WHERE pipeline_id = ?",
                    avgCohereScore, pipelineId
            );

            double sign = advantage >= 0 ? 1.0 : -1.0;
            double newThreshold = clamp(
                    currentThreshold + sign * ALPHA * Math.abs(advantage) * (usedThreshold - currentThreshold),
                    THRESHOLD_MIN, THRESHOLD_MAX
            );

            currentThreshold = newThreshold;
            persistThreshold();

            log.info("Phase1 RL 업데이트 — advantage:{} → threshold:{}",
                    String.format("%.3f", advantage),
                    String.format("%.4f", newThreshold));

        } catch (Exception e) {
            log.error("Phase1 RL 업데이트 실패 — pipelineId: {}, 원인: {}", pipelineId, e.getMessage());
        }
    }

    public SearchParams getCurrentParams() {
        return new SearchParams(1.0, 1.0, currentThreshold);
    }

    private void persistThreshold() {
        try {
            jdbcTemplate.update("""
                    UPDATE rl_params SET
                        similarity_threshold = ?,
                        total_runs           = total_runs + 1,
                        updated_at           = NOW()
                    WHERE id = 1
                    """,
                    currentThreshold
            );
        } catch (Exception e) {
            log.warn("Phase1 RL 파라미터 DB 저장 실패: {}", e.getMessage());
        }
    }

    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}
