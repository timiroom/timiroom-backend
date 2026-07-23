package com.timiroom.domain.github;

import com.timiroom.domain.project.ProjectMember;
import com.timiroom.domain.project.ProjectMemberRepository;
import com.timiroom.domain.project.ProjectRole;
import com.timiroom.domain.project.ProjectService;
import com.timiroom.infra.github.GithubClient;
import com.timiroom.infra.github.dto.GithubIssueInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GithubIssueServiceTest {

    private static final long PROJECT_ID = 1L;
    private static final long MEMBER_ID = 10L;
    private static final long REPO_ID = 99L;
    private static final long INSTALLATION_ID = 146037712L;

    @Mock ProjectService projectService;
    @Mock ProjectMemberRepository projectMemberRepository;
    @Mock ProjectRepoLinkRepository projectRepoLinkRepository;
    @Mock GithubRepoRepository githubRepoRepository;
    @Mock GithubClient githubClient;
    @InjectMocks GithubIssueService service;

    private void givenLinkedRepo() {
        when(projectRepoLinkRepository.findByProjectIdAndGithubRepoId(PROJECT_ID, REPO_ID))
                .thenReturn(Optional.of(ProjectRepoLink.builder().projectId(PROJECT_ID).githubRepoId(REPO_ID).build()));
        when(githubRepoRepository.findById(REPO_ID)).thenReturn(Optional.of(GithubRepo.builder().id(REPO_ID)
                .githubRepoId(555L).fullName("timiroom/timiroom-backend").installationId(INSTALLATION_ID).build()));
    }

    @Test
    void create_PM은_연결된_레포에_GitHub_issue를_생성한다() {
        givenLinkedRepo();
        when(projectMemberRepository.findByProjectIdAndMemberId(PROJECT_ID, MEMBER_ID)).thenReturn(Optional.of(
                ProjectMember.builder().projectId(PROJECT_ID).memberId(MEMBER_ID).projectRole(ProjectRole.PM).build()));
        when(githubClient.createIssue(eq("timiroom/timiroom-backend"), eq(INSTALLATION_ID), eq("로그인 오류"),
                eq("재현 절차"), any())).thenReturn(new GithubIssueInfo(12, "로그인 오류", "재현 절차", "open",
                "https://github.com/timiroom/timiroom-backend/issues/12", "chunwol", "2026-07-12T10:00:00Z", List.of("bug")));

        var result = service.create(PROJECT_ID, MEMBER_ID, REPO_ID, "로그인 오류", "재현 절차", List.of("bug"));

        assertThat(result.number()).isEqualTo(12);
        assertThat(result.repoFullName()).isEqualTo("timiroom/timiroom-backend");
        verify(projectService).getById(PROJECT_ID, MEMBER_ID);
    }

    @Test
    void create_PM이_아니면_거부한다() {
        givenLinkedRepo();
        when(projectMemberRepository.findByProjectIdAndMemberId(PROJECT_ID, MEMBER_ID)).thenReturn(Optional.of(
                ProjectMember.builder().projectId(PROJECT_ID).memberId(MEMBER_ID).projectRole(ProjectRole.BACKEND).build()));

        assertThatThrownBy(() -> service.create(PROJECT_ID, MEMBER_ID, REPO_ID, "제목", "", List.of()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("PM");
    }
}
