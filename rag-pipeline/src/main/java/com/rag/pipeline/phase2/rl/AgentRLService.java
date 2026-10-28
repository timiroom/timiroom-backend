package com.rag.pipeline.phase2.rl;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase2 에이전트(DBA, API) temperature 자동 튜닝 — Epsilon-Greedy + EMA + Baseline
 *
 * - 탐색 (10%): 후보 temperature 중 무작위 선택
 * - 활용 (90%): 현재 최적 temperature 사용
 * - 보상: QaAgent 품질 점수 (0.0 ~ 1.0)
 * - Baseline: 최근 20회 품질 점수의 슬라이딩 윈도우 평균
 * - 업데이트: advantage = score - baseline → EMA로 temperature 조정
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRLService {

    private final JdbcTemplate jdbcTemplate;

    private static final double ALPHA          = 0.1;   // EMA 학습률
    private static final double EPSILON        = 0.10;  // 탐색 비율
    private static final int    BASELINE_WINDOW = 20;   // 슬라이딩 윈도우 크기
    private static final double EXPLORE_SIGMA  = 0.1;   // 가우시안 탐색 표준편차

    private final Map<String, Double> currentTemperatures = new ConcurrentHashMap<>();
    private final ArrayDeque<Double>  baselineWindow      = new ArrayDeque<>(BASELINE_WINDOW);
    private final Random              random              = new Random();

    @PostConstruct
    public void init() {
        loadFromDb("dba");
        loadFromDb("api");
    }

    // ── public API ────────────────────────────────────────────────

    /**
     * epsilon-greedy: 10% 탐색, 90% 현재 최적값
     * 탐색 시 현재값 ±σ(0.1) 범위의 가우시안 샘플 사용 → 소수점 4자리 연속 탐색
     */
    public double getTemperature(String agentName) {
        double current = currentTemperatures.getOrDefault(agentName, 0.1);
        if (random.nextDouble() < EPSILON) {
            double noise    = random.nextGaussian() * EXPLORE_SIGMA;
            double explored = clamp(
                    Math.round((current + noise) * 10000.0) / 10000.0,
                    0.0, 1.0
            );
            log.debug("에이전트 RL 탐색 — {}: base={} noise={} → explored={}",
                    agentName,
                    String.format("%.4f", current),
                    String.format("%.4f", noise),
                    String.format("%.4f", explored));
            return explored;
        }
        return current;
    }

    /** 에이전트 실행 시 사용한 temperature 기록 */
    public void logAgentRun(String pipelineId, String agentName, double temperature) {
        if (pipelineId == null || pipelineId.isBlank()) return;
        try {
            jdbcTemplate.update("""
                    INSERT INTO agent_rl_log (pipeline_id, agent_name, temperature)
                    VALUES (?, ?, ?)
                    ON CONFLICT (pipeline_id, agent_name) DO UPDATE
                        SET temperature = EXCLUDED.temperature,
                            created_at  = NOW()
                    """,
                    pipelineId, agentName, temperature
            );
        } catch (Exception e) {
            log.warn("에이전트 RL 로그 저장 실패 — {}/{}: {}", pipelineId, agentName, e.getMessage());
        }
    }

    /**
     * QA 품질 점수 수신 → DBA/API temperature 업데이트
     *
     * advantage = qualityScore - baseline
     * new_temp  = current + sign(advantage) × α × |advantage| × (used - current)
     */
    public void applyQaFeedback(String pipelineId, double qualityScore) {
        if (pipelineId == null || pipelineId.isBlank()) return;

        // 슬라이딩 윈도우 baseline 업데이트
        synchronized (baselineWindow) {
            if (baselineWindow.size() >= BASELINE_WINDOW) baselineWindow.pollFirst();
            baselineWindow.addLast(qualityScore);
        }

        double baseline;
        synchronized (baselineWindow) {
            baseline = baselineWindow.stream().mapToDouble(d -> d).average().orElse(0.5);
        }
        double advantage = qualityScore - baseline;

        log.info("QA 피드백 수신 — score:{}, baseline:{}, advantage:{}",
                String.format("%.2f", qualityScore),
                String.format("%.2f", baseline),
                String.format("%.3f", advantage));

        for (String agentName : List.of("dba", "api")) {
            updateAgentTemp(pipelineId, agentName, advantage, qualityScore);
        }
    }

    public double getCurrentTemperature(String agentName) {
        return currentTemperatures.getOrDefault(agentName, 0.1);
    }

    // ── private ──────────────────────────────────────────────────

    private void loadFromDb(String agentName) {
        try {
            Double temp = jdbcTemplate.queryForObject(
                    "SELECT temperature FROM agent_rl_params WHERE agent_name = ?",
                    Double.class, agentName
            );
            currentTemperatures.put(agentName, temp != null ? temp : 0.1);
            log.info("에이전트 RL 파라미터 로드 — {}: temperature={}", agentName,
                    currentTemperatures.get(agentName));
        } catch (Exception e) {
            currentTemperatures.put(agentName, 0.1);
            log.warn("에이전트 RL 파라미터 로드 실패 — {}: {}", agentName, e.getMessage());
        }
    }

    private void updateAgentTemp(String pipelineId, String agentName,
                                  double advantage, double qualityScore) {
        try {
            Double usedTemp = jdbcTemplate.queryForObject(
                    "SELECT temperature FROM agent_rl_log WHERE pipeline_id = ? AND agent_name = ?",
                    Double.class, pipelineId, agentName
            );
            if (usedTemp == null) {
                log.debug("에이전트 RL 로그 없음 — 업데이트 스킵: {}/{}", pipelineId, agentName);
                return;
            }

            jdbcTemplate.update(
                    "UPDATE agent_rl_log SET quality_score = ? WHERE pipeline_id = ? AND agent_name = ?",
                    advantage, pipelineId, agentName
            );

            double current = currentTemperatures.getOrDefault(agentName, 0.1);
            double sign    = advantage >= 0 ? 1.0 : -1.0;
            double newTemp = clamp(
                    current + sign * ALPHA * Math.abs(advantage) * (usedTemp - current),
                    0.0, 1.0
            );

            currentTemperatures.put(agentName, newTemp);

            jdbcTemplate.update("""
                    UPDATE agent_rl_params SET
                        temperature = ?,
                        total_runs  = total_runs + 1,
                        total_score = total_score + ?,
                        updated_at  = NOW()
                    WHERE agent_name = ?
                    """,
                    newTemp, qualityScore, agentName
            );

            log.info("에이전트 RL 업데이트 — {} | advantage:{} → temperature:{}",
                    agentName,
                    String.format("%.3f", advantage),
                    String.format("%.3f", newTemp));

        } catch (Exception e) {
            log.error("에이전트 RL 업데이트 실패 — {}: {}", agentName, e.getMessage());
        }
    }

    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}
