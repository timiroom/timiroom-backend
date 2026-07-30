package com.timiroom.domain.github.dto;

/** 이슈 참조 또는 feature branch 이름이 일치해 함께 확인할 가치가 있는 다른 레포 PR. */
public record RelatedPullRequestResponse(
        Long repoId,
        String repoFullName,
        int number,
        String title,
        String htmlUrl
) {}
