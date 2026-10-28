package com.timiroom.domain.github.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 프로젝트에서 가장 최근에 검사된 PR의 정합성 결과 — 명세 패널 배지에 사용한다. */
public record ProjectConsistencySummary(
        Long repoId,
        String repoFullName,
        int pullNumber,
        int score,
        String reviewUrl,
        String checkRunUrl,
        LocalDateTime checkedAt,
        String evaluator,
        List<ConsistencyFinding> findings
) {}
