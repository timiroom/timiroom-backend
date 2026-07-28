package com.timiroom.domain.github;

import com.timiroom.domain.github.dto.ProjectGithubIssueResponse;
import com.timiroom.domain.member.Member;
import com.timiroom.domain.member.MemberRepository;
import com.timiroom.domain.project.ProjectMember;
import com.timiroom.domain.project.ProjectMemberRepository;
import com.timiroom.domain.project.ProjectRole;
import com.timiroom.domain.project.ProjectService;
import com.timiroom.infra.github.GithubClient;
import com.timiroom.infra.github.dto.GithubIssueInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/** 프로젝트에 연결된 레포 단위 GitHub issue 읽기/생성. */
@Service
@RequiredArgsConstructor
public class GithubIssueService {

    private final ProjectService projectService;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepoLinkRepository projectRepoLinkRepository;
    private final GithubRepoRepository githubRepoRepository;
    private final MemberRepository memberRepository;
    private final GithubClient githubClient;

    @Transactional(readOnly = true)
    public List<ProjectGithubIssueResponse> list(Long projectId, Long memberId) {
        projectService.getById(projectId, memberId);
        return projectRepoLinkRepository.findByProjectId(projectId).stream()
                .map(link -> githubRepoRepository.findById(link.getGithubRepoId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .flatMap(repo -> githubClient.listIssues(repo.getFullName(), repo.getInstallationId()).stream()
                        .map(issue -> toResponse(repo, issue)))
                .sorted(Comparator.comparing(ProjectGithubIssueResponse::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Transactional
    public ProjectGithubIssueResponse create(Long projectId, Long memberId, Long repoId,
                                              String title, String body, List<String> labels) {
        return create(projectId, memberId, repoId, title, body, labels, null);
    }

    @Transactional
    public ProjectGithubIssueResponse create(Long projectId, Long memberId, Long repoId,
                                              String title, String body, List<String> labels,
                                              Long ownerMemberId) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("이슈 제목은 필수입니다");
        GithubRepo repo = findLinkedRepo(projectId, memberId, repoId);
        requirePm(projectId, memberId);
        List<String> assignees = findAssignees(projectId, ownerMemberId);
        GithubIssueInfo created = githubClient.createIssue(repo.getFullName(), repo.getInstallationId(),
                title.trim(), body, labels == null ? List.of() : labels, assignees);
        return toResponse(repo, created);
    }

    @Transactional
    public ProjectGithubIssueResponse update(Long projectId, Long memberId, Long repoId, int issueNumber,
                                              String title, String body, List<String> labels,
                                              Long ownerMemberId) {
        if (issueNumber < 1) throw new IllegalArgumentException("올바른 이슈 번호가 필요합니다");
        if (title != null && title.isBlank()) throw new IllegalArgumentException("이슈 제목은 비울 수 없습니다");
        GithubRepo repo = findLinkedRepo(projectId, memberId, repoId);
        requirePm(projectId, memberId);
        GithubIssueInfo updated = githubClient.updateIssue(repo.getFullName(), repo.getInstallationId(), issueNumber,
                title == null ? null : title.trim(), body, labels, findAssignees(projectId, ownerMemberId));
        return toResponse(repo, updated);
    }

    private List<String> findAssignees(Long projectId, Long ownerMemberId) {
        if (ownerMemberId == null) return List.of();
        projectMemberRepository.findByProjectIdAndMemberId(projectId, ownerMemberId)
                .orElseThrow(() -> new IllegalArgumentException("담당자는 프로젝트 멤버여야 합니다"));
        Member owner = memberRepository.findById(ownerMemberId)
                .orElseThrow(() -> new IllegalArgumentException("담당자 계정을 찾을 수 없습니다"));
        return owner.getGithubLogin() == null || owner.getGithubLogin().isBlank()
                ? List.of()
                : List.of(owner.getGithubLogin());
    }

    private GithubRepo findLinkedRepo(Long projectId, Long memberId, Long repoId) {
        projectService.getById(projectId, memberId);
        projectRepoLinkRepository.findByProjectIdAndGithubRepoId(projectId, repoId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트에 연결된 레포가 아닙니다: " + repoId));
        return githubRepoRepository.findById(repoId)
                .orElseThrow(() -> new IllegalArgumentException("GitHub 레포를 찾을 수 없습니다: " + repoId));
    }

    private void requirePm(Long projectId, Long memberId) {
        ProjectMember member = projectMemberRepository.findByProjectIdAndMemberId(projectId, memberId)
                .orElseThrow(() -> new SecurityException("이슈를 관리할 프로젝트 권한이 없습니다"));
        if (member.getProjectRole() != ProjectRole.PM) {
            throw new SecurityException("이슈 관리는 PM만 할 수 있습니다");
        }
    }

    private ProjectGithubIssueResponse toResponse(GithubRepo repo, GithubIssueInfo issue) {
        return new ProjectGithubIssueResponse(repo.getId(), repo.getFullName(), issue.number(), issue.title(),
                issue.body(), issue.state(), issue.htmlUrl(), issue.authorLogin(), issue.createdAt(), issue.labels());
    }
}
