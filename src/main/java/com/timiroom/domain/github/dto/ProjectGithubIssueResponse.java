package com.timiroom.domain.github.dto;

import java.util.List;

/** 프로젝트 관점에서 표시하는 연결 레포 이슈. */
public record ProjectGithubIssueResponse(
        Long repoId,
        String repoFullName,
        int number,
        String title,
        String body,
        String state,
        String htmlUrl,
        String authorLogin,
        String createdAt,
        List<String> labels
) {}
