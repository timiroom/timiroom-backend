package com.rag.pipeline.phase1.form;

import jakarta.validation.constraints.NotBlank;

/**
 * Step2 - 문제정의 (6개 질문)
 * SearchAgent 시장조사 방향 + PrdAgent Goals/KPI 생성 기준으로 사용
 */
public record ProblemDefinition(
    @NotBlank String currentPainPoint,  // Q1. 지금 어떤 불편함?
    @NotBlank String currentSolution,   // Q2. 지금 어떻게 해결?
    @NotBlank String idealState,        // Q3. 서비스가 잘 된다면?
    String businessImpact,              // Q4. 손실 규모 (선택)
    String motivation,                  // Q5. 만들게 된 계기 (선택)
    String competitorGap                // Q6. 경쟁사 대비 차별점 (선택)
) {}
