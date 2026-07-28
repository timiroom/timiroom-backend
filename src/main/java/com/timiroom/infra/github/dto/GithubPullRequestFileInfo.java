package com.timiroom.infra.github.dto;

/** PR 변경 파일의 정합성 검사에 필요한 diff 요약. */
public record GithubPullRequestFileInfo(
        String filename,
        String status,
        int additions,
        int deletions,
        String patch,
        String content,
        String baseContent,
        boolean patchTruncated
) {
    public GithubPullRequestFileInfo(String filename, String status, int additions, int deletions, String patch) {
        this(filename, status, additions, deletions, patch, null, null,
                (patch == null || patch.isBlank()) && additions + deletions > 0);
    }

    public GithubPullRequestFileInfo withContents(String content, String baseContent) {
        return new GithubPullRequestFileInfo(filename, status, additions, deletions, patch,
                content, baseContent, patchTruncated);
    }
}
