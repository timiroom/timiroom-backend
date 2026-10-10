package com.timiroom.infra.github.dto;

/** GitHub pull request 정보 요약. */
public record GithubPullRequestInfo(
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
        String baseSha
) {
    public GithubPullRequestInfo(int number,String title,String body,String state,boolean draft,String headSha,
            String headRef,String baseRef,String htmlUrl,String authorLogin,String updatedAt) {
        this(number,title,body,state,draft,headSha,headRef,baseRef,htmlUrl,authorLogin,updatedAt,null);
    }
}
