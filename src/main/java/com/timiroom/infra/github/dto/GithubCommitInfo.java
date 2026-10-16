package com.timiroom.infra.github.dto;

/** GitHub API의 커밋 정보 요약. */
public record GithubCommitInfo(
        String sha,
        String message,
        String authorName,
        String authorLogin,
        String committedAt,
        String htmlUrl
) {}
