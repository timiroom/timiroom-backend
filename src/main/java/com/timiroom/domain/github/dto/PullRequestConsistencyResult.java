package com.timiroom.domain.github.dto;

import java.util.List;

/** 정합성 검사와 GitHub review comment 게시 결과. */
public record PullRequestConsistencyResult(
        Long repoId,
        int pullNumber,
        String headSha,
        int score,
        boolean reviewPosted,
        boolean skippedDuplicate,
        String reviewUrl,
        String checkRunUrl,
        List<ConsistencyFinding> findings
) {}
