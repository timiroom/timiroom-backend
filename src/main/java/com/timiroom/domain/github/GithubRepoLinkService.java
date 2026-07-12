package com.timiroom.domain.github;

import com.timiroom.domain.github.dto.ProjectRepoResponse;
import com.timiroom.domain.project.ProjectMember;
import com.timiroom.domain.project.ProjectMemberRepository;
import com.timiroom.domain.project.ProjectRole;
import com.timiroom.domain.project.ProjectService;
import com.timiroom.infra.github.GithubClient;
import com.timiroom.infra.github.dto.GithubRepoInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GithubRepoLinkService {

    private final ProjectService projectService;
    private final ProjectMemberRepository projectMemberRepository;
    private final GithubInstallationRepository githubInstallationRepository;
    private final GithubRepoRepository githubRepoRepository;
    private final ProjectRepoLinkRepository projectRepoLinkRepository;
    private final GithubClient githubClient;

    /** 프로젝트에 연결된 레포 목록 (프로젝트 멤버면 조회 가능) */
    @Transactional(readOnly = true)
    public List<ProjectRepoResponse> getLinks(Long projectId, Long memberId) {
        projectService.getById(projectId, memberId); // 멤버십 + 존재 검증

        List<ProjectRepoLink> links = projectRepoLinkRepository.findByProjectId(projectId);
        if (links.isEmpty()) return List.of();

        Map<Long, GithubRepo> repoById = githubRepoRepository
                .findByIdIn(links.stream().map(ProjectRepoLink::getGithubRepoId).toList())
                .stream().collect(Collectors.toMap(GithubRepo::getId, r -> r));

        return links.stream()
                .map(link -> {
                    GithubRepo repo = repoById.get(link.getGithubRepoId());
                    if (repo == null) return null;
                    return toResponse(repo, link.getRoleHint());
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 프로젝트에 레포 연결 (PM 전용).
     * githubRepoId가 해당 설치에서 실제 접근 가능한지 GitHub에 확인한 뒤 등록한다.
     */
    @Transactional
    public ProjectRepoResponse link(Long projectId, Long actorMemberId,
                                    Long installationId, Long githubRepoId, String roleHint) {
        projectService.getById(projectId, actorMemberId);
        requirePm(projectId, actorMemberId);

        githubInstallationRepository.findByInstallationId(installationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "등록되지 않은 installation입니다. 먼저 동기화를 실행해주세요: " + installationId));

        // 클라이언트가 준 메타데이터를 믿지 않고, 설치가 실제로 접근 가능한 레포인지 GitHub에서 확인
        GithubRepoInfo remote = githubClient.listInstallationRepositories(installationId).stream()
                .filter(r -> r.repoId() == githubRepoId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "해당 설치에서 접근할 수 없는 레포입니다: " + githubRepoId));

        GithubRepo repo = githubRepoRepository.findByGithubRepoId(githubRepoId)
                .map(existing -> {
                    existing.updateMetadata(remote.fullName(), remote.defaultBranch(), remote.isPrivate(), installationId);
                    return existing;
                })
                .orElseGet(() -> githubRepoRepository.save(GithubRepo.builder()
                        .githubRepoId(githubRepoId)
                        .fullName(remote.fullName())
                        .defaultBranch(remote.defaultBranch())
                        .isPrivate(remote.isPrivate())
                        .installationId(installationId)
                        .build()));

        if (projectRepoLinkRepository.existsByProjectIdAndGithubRepoId(projectId, repo.getId())) {
            throw new IllegalStateException("이미 연결된 레포입니다: " + remote.fullName());
        }

        projectRepoLinkRepository.save(ProjectRepoLink.builder()
                .projectId(projectId)
                .githubRepoId(repo.getId())
                .roleHint(normalizeRoleHint(roleHint))
                .build());

        log.info("프로젝트 {} 에 레포 연결 — {} (installation {})", projectId, remote.fullName(), installationId);
        return toResponse(repo, normalizeRoleHint(roleHint));
    }

    /** 프로젝트에서 레포 연결 해제 (PM 전용). repoInternalId = github_repo 내부 id */
    @Transactional
    public void unlink(Long projectId, Long actorMemberId, Long repoInternalId) {
        projectService.getById(projectId, actorMemberId);
        requirePm(projectId, actorMemberId);

        ProjectRepoLink link = projectRepoLinkRepository
                .findByProjectIdAndGithubRepoId(projectId, repoInternalId)
                .orElseThrow(() -> new IllegalArgumentException("연결된 레포가 아닙니다: " + repoInternalId));

        projectRepoLinkRepository.delete(link);
        log.info("프로젝트 {} 에서 레포 연결 해제 — githubRepoPk {}", projectId, repoInternalId);
    }

    private void requirePm(Long projectId, Long memberId) {
        ProjectMember actor = projectMemberRepository.findByProjectIdAndMemberId(projectId, memberId)
                .orElseThrow(() -> new SecurityException("프로젝트 멤버만 레포를 관리할 수 있습니다"));
        if (actor.getProjectRole() != ProjectRole.PM) {
            throw new SecurityException("레포 연결/해제는 PM만 할 수 있습니다");
        }
    }

    private String normalizeRoleHint(String roleHint) {
        if (roleHint == null || roleHint.isBlank()) return null;
        return roleHint.trim().toUpperCase();
    }

    private ProjectRepoResponse toResponse(GithubRepo repo, String roleHint) {
        return new ProjectRepoResponse(
                repo.getId(),
                repo.getGithubRepoId(),
                repo.getFullName(),
                repo.getDefaultBranch(),
                repo.isPrivate(),
                repo.getInstallationId(),
                roleHint);
    }
}
