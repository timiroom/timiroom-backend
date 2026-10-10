package com.timiroom.domain.github.dto;

import java.util.List;

/** 프로젝트 관점에서 표시하는 연결 레포 PR. */
public record ProjectPullRequestResponse(
        Long repoId,
        String repoFullName,
        int number,
        String title,
        String body,
        String state,
        boolean draft,
        String headSha,
        String headRef,
        String baseRef,
        String htmlUrl,
        String authorLogin,
        String updatedAt,
        List<RelatedPullRequestResponse> relatedPullRequests,
        PullRequestConsistencyResult consistencyResult
) {}
