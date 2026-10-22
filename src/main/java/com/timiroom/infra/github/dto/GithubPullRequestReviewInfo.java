package com.timiroom.infra.github.dto;

/** GitHub에 게시된 PR review 요약. */
public record GithubPullRequestReviewInfo(
        long reviewId,
        String htmlUrl,
        String state
) {}
