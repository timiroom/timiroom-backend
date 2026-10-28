package com.timiroom.infra.github.dto;

/** GitHub API의 레포지토리 정보 요약 */
public record GithubRepoInfo(
        long repoId,
        String fullName,
        String defaultBranch,
        boolean isPrivate
) {}
