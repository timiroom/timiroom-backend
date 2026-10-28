package com.timiroom.infra.github.dto;

/** GitHub Checks API로 게시한 check run 요약. */
public record GithubCheckRunInfo(
        long checkRunId,
        String htmlUrl,
        String conclusion
) {}
