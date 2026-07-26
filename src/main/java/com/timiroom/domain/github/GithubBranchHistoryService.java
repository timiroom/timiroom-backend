package com.timiroom.domain.github;

import com.timiroom.domain.project.ProjectService;
import com.timiroom.infra.github.GithubClient;
import com.timiroom.infra.github.dto.GithubBranchInfo;
import com.timiroom.infra.github.dto.GithubCommitInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 프로젝트에 연결된 GitHub 레포의 읽기 전용 브랜치/커밋 조회. */
@Service
@RequiredArgsConstructor
public class GithubBranchHistoryService {

    private final ProjectService projectService;
    private final ProjectRepoLinkRepository projectRepoLinkRepository;
    private final GithubRepoRepository githubRepoRepository;
    private final GithubClient githubClient;

    @Transactional(readOnly = true)
    public List<GithubBranchInfo> getBranches(Long projectId, Long memberId, Long repoId) {
        GithubRepo repo = findLinkedRepo(projectId, memberId, repoId);
        return githubClient.listBranches(repo.getFullName(), repo.getInstallationId());
    }

    @Transactional(readOnly = true)
    public List<GithubCommitInfo> getCommits(Long projectId, Long memberId, Long repoId, String branch) {
        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException("branch는 필수입니다");
        }
        GithubRepo repo = findLinkedRepo(projectId, memberId, repoId);
        return githubClient.listCommits(repo.getFullName(), repo.getInstallationId(), branch.trim());
    }

    private GithubRepo findLinkedRepo(Long projectId, Long memberId, Long repoId) {
        projectService.getById(projectId, memberId); // 프로젝트 멤버십 + 존재 검증
        projectRepoLinkRepository.findByProjectIdAndGithubRepoId(projectId, repoId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트에 연결된 레포가 아닙니다: " + repoId));
        return githubRepoRepository.findById(repoId)
                .orElseThrow(() -> new IllegalArgumentException("GitHub 레포를 찾을 수 없습니다: " + repoId));
    }
}
