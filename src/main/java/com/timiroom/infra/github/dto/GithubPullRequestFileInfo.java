package com.timiroom.infra.github.dto;

/** PR 변경 파일의 정합성 검사에 필요한 diff 요약. */
public record GithubPullRequestFileInfo(
        String filename,
        String status,
        int additions,
        int deletions,
        String patch
) {}
