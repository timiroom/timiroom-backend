package com.timiroom.infra.github.dto;

/** GitHub API의 설치(installation) 정보 요약 */
public record GithubInstallationInfo(
        long installationId,
        String accountLogin,
        String accountType
) {}
