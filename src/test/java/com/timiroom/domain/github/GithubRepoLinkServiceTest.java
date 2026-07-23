package com.timiroom.domain.github;

import com.timiroom.domain.github.dto.ProjectRepoResponse;
import com.timiroom.domain.project.Project;
import com.timiroom.domain.project.ProjectMember;
import com.timiroom.domain.project.ProjectMemberRepository;
import com.timiroom.domain.project.ProjectRole;
import com.timiroom.domain.project.ProjectService;
import com.timiroom.infra.github.GithubClient;
import com.timiroom.infra.github.dto.GithubRepoInfo;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GithubRepoLinkServiceTest {

    @Mock ProjectService projectService;
    @Mock ProjectMemberRepository projectMemberRepository;
    @Mock GithubInstallationRepository githubInstallationRepository;
    @Mock GithubRepoRepository githubRepoRepository;
    @Mock ProjectRepoLinkRepository projectRepoLinkRepository;
    @Mock GithubClient githubClient;

    @InjectMocks GithubRepoLinkService service;

    private static final long PROJECT_ID = 1L;
    private static final long PM_MEMBER_ID = 10L;
    private static final long TEAM_ID = 7L;
    private static final long INSTALLATION_ID = 146037712L;
    private static final long GH_REPO_ID = 555L;

    private void givenPm(ProjectRole role) {
        when(projectMemberRepository.findByProjectIdAndMemberId(PROJECT_ID, PM_MEMBER_ID))
                .thenReturn(Optional.of(ProjectMember.builder()
                        .projectId(PROJECT_ID).memberId(PM_MEMBER_ID).projectRole(role).build()));
    }

    /** link()가 요구하는 프로젝트 조회 — 프로젝트는 TEAM_ID 워크스페이스 소속으로 설정 */
    private void givenProjectInTeam() {
        when(projectService.getById(PROJECT_ID, PM_MEMBER_ID))
                .thenReturn(Project.builder().projectId(PROJECT_ID).teamId(TEAM_ID).projectName("timiroom").build());
    }

    private void givenInstallationRegistered() {
        givenInstallationRegistered(TEAM_ID);
    }

    private void givenInstallationRegistered(Long ownerTeamId) {
        when(githubInstallationRepository.findByInstallationId(INSTALLATION_ID))
                .thenReturn(Optional.of(GithubInstallation.builder()
                        .installationId(INSTALLATION_ID).accountLogin("timiroom").teamId(ownerTeamId).build()));
    }

    private void givenRepoAccessible() {
        when(githubClient.listInstallationRepositories(INSTALLATION_ID))
                .thenReturn(List.of(new GithubRepoInfo(GH_REPO_ID, "timiroom/timiroom-backend", "develop", false)));
    }

    @Test
    void link_성공하면_레포와_연결을_저장한다() {
        givenPm(ProjectRole.PM);
        givenProjectInTeam();
        givenInstallationRegistered();
        givenRepoAccessible();
        when(githubRepoRepository.findByGithubRepoId(GH_REPO_ID)).thenReturn(Optional.empty());
        when(githubRepoRepository.save(any())).thenAnswer(inv -> {
            GithubRepo r = inv.getArgument(0);
            return GithubRepo.builder()
                    .id(99L)
                    .githubRepoId(r.getGithubRepoId())
                    .fullName(r.getFullName())
                    .defaultBranch(r.getDefaultBranch())
                    .isPrivate(r.isPrivate())
                    .installationId(r.getInstallationId())
                    .build();
        });
        when(projectRepoLinkRepository.existsByProjectIdAndGithubRepoId(PROJECT_ID, 99L)).thenReturn(false);

        ProjectRepoResponse res = service.link(PROJECT_ID, PM_MEMBER_ID, INSTALLATION_ID, GH_REPO_ID, "backend");

        assertThat(res.fullName()).isEqualTo("timiroom/timiroom-backend");
        assertThat(res.roleHint()).isEqualTo("BACKEND"); // 대문자 정규화
        assertThat(res.id()).isEqualTo(99L);
        verify(projectRepoLinkRepository).save(any(ProjectRepoLink.class));
    }

    @Test
    void link_PM이_아니면_거부한다() {
        givenPm(ProjectRole.BACKEND);

        assertThatThrownBy(() -> service.link(PROJECT_ID, PM_MEMBER_ID, INSTALLATION_ID, GH_REPO_ID, null))
                .isInstanceOf(SecurityException.class);

        verify(githubClient, never()).listInstallationRepositories(anyLong());
        verify(projectRepoLinkRepository, never()).save(any());
    }

    @Test
    void link_이미_연결된_레포면_충돌() {
        givenPm(ProjectRole.PM);
        givenProjectInTeam();
        givenInstallationRegistered();
        givenRepoAccessible();
        when(githubRepoRepository.findByGithubRepoId(GH_REPO_ID))
                .thenReturn(Optional.of(GithubRepo.builder().id(99L).githubRepoId(GH_REPO_ID)
                        .fullName("timiroom/timiroom-backend").defaultBranch("develop")
                        .installationId(INSTALLATION_ID).build()));
        when(projectRepoLinkRepository.existsByProjectIdAndGithubRepoId(PROJECT_ID, 99L)).thenReturn(true);

        assertThatThrownBy(() -> service.link(PROJECT_ID, PM_MEMBER_ID, INSTALLATION_ID, GH_REPO_ID, null))
                .isInstanceOf(IllegalStateException.class);

        verify(projectRepoLinkRepository, never()).save(any());
    }

    @Test
    void link_설치에서_접근불가한_레포면_거부한다() {
        givenPm(ProjectRole.PM);
        givenProjectInTeam();
        givenInstallationRegistered();
        when(githubClient.listInstallationRepositories(INSTALLATION_ID))
                .thenReturn(List.of(new GithubRepoInfo(999L, "other/repo", "main", false))); // 다른 repo만

        assertThatThrownBy(() -> service.link(PROJECT_ID, PM_MEMBER_ID, INSTALLATION_ID, GH_REPO_ID, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(githubRepoRepository, never()).save(any());
    }

    @Test
    void link_설치가_다른_워크스페이스_소유면_거부한다() {
        givenPm(ProjectRole.PM);
        givenProjectInTeam(); // 프로젝트는 TEAM_ID(7) 소속
        givenInstallationRegistered(99L); // installation은 다른 팀(99) 소유

        assertThatThrownBy(() -> service.link(PROJECT_ID, PM_MEMBER_ID, INSTALLATION_ID, GH_REPO_ID, null))
                .isInstanceOf(SecurityException.class);

        verify(githubClient, never()).listInstallationRepositories(anyLong());
        verify(projectRepoLinkRepository, never()).save(any());
    }
}
