package com.timiroom.infra.github.dto;

/** GitHub API의 브랜치 정보 요약. */
public record GithubBranchInfo(
        String name,
        String headSha,
        boolean isProtected
) {}
