package com.timiroom.domain.github.dto;

/** 프로젝트에 연결된 레포 응답 (id는 연결 해제 시 사용하는 github_repo 내부 id) */
public record ProjectRepoResponse(
        Long id,
        Long githubRepoId,
        String fullName,
        String defaultBranch,
        boolean isPrivate,
        Long installationId,
        String roleHint
) {}
