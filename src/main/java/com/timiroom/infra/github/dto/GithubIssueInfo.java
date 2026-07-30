package com.timiroom.infra.github.dto;

import java.util.List;

/** GitHub issue 정보 요약. PR은 GitHub Issues API 응답에서 제외한다. */
public record GithubIssueInfo(
        int number,
        String title,
        String body,
        String state,
        String htmlUrl,
        String authorLogin,
        String createdAt,
        List<String> labels
) {}
