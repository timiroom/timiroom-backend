package com.timiroom.domain.github;

import com.timiroom.domain.team.service.TeamService;
import com.timiroom.infra.github.GithubClient;
import com.timiroom.infra.github.dto.GithubInstallationInfo;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GithubInstallationServiceTest {

    @Mock GithubInstallationRepository githubInstallationRepository;
    @Mock GithubRepoRepository githubRepoRepository;
    @Mock ProjectRepoLinkRepository projectRepoLinkRepository;
    @Mock TeamService teamService;
    @Mock GithubClient githubClient;

    @InjectMocks GithubInstallationService service;

    private static final long TEAM_ID = 7L;
    private static final long OTHER_TEAM_ID = 8L;
    private static final long MEMBER_ID = 10L;
    private static final long INSTALLATION_ID = 146037712L;

    @Test
    void getByTeam_멤버십_없으면_거부한다() {
        when(teamService.requireMembership(TEAM_ID, MEMBER_ID)).thenThrow(new SecurityException("팀 접근 권한이 없습니다"));

        assertThatThrownBy(() -> service.getByTeam(TEAM_ID, MEMBER_ID)).isInstanceOf(SecurityException.class);
        verify(githubInstallationRepository, never()).findByTeamId(anyLong());
    }

    @Test
    void getUnassigned_은_팀ID가_null인_설치만_반환한다() {
        GithubInstallation unassigned = GithubInstallation.builder()
                .installationId(INSTALLATION_ID).accountLogin("timiroom").build();
        when(githubInstallationRepository.findByTeamIdIsNull()).thenReturn(List.of(unassigned));

        List<GithubInstallation> result = service.getUnassigned(TEAM_ID, MEMBER_ID);

        assertThat(result).containsExactly(unassigned);
        verify(teamService).requireOwner(TEAM_ID, MEMBER_ID);
    }

    @Test
    void getUnassigned_오너가_아니면_거부한다() {
        when(teamService.requireOwner(TEAM_ID, MEMBER_ID)).thenThrow(new SecurityException("오너 권한이 필요합니다"));

        assertThatThrownBy(() -> service.getUnassigned(TEAM_ID, MEMBER_ID))
                .isInstanceOf(SecurityException.class);
        verify(githubInstallationRepository, never()).findByTeamIdIsNull();
    }

    @Test
    void linkToTeam_오너가_아니면_거부한다() {
        when(teamService.requireOwner(TEAM_ID, MEMBER_ID)).thenThrow(new SecurityException("오너 권한이 필요합니다"));

        assertThatThrownBy(() -> service.linkToTeam(TEAM_ID, MEMBER_ID, INSTALLATION_ID))
                .isInstanceOf(SecurityException.class);
        verify(githubInstallationRepository, never()).findByInstallationId(anyLong());
    }

    @Test
    void linkToTeam_미할당_설치를_연결한다() {
        GithubInstallation installation = GithubInstallation.builder()
                .installationId(INSTALLATION_ID).accountLogin("timiroom").build();
        when(githubInstallationRepository.findByInstallationId(INSTALLATION_ID)).thenReturn(Optional.of(installation));

        GithubInstallation result = service.linkToTeam(TEAM_ID, MEMBER_ID, INSTALLATION_ID);

        assertThat(result.getTeamId()).isEqualTo(TEAM_ID);
    }

    @Test
    void linkToTeam_이미_다른_팀에_연결되어_있으면_충돌() {
        GithubInstallation installation = GithubInstallation.builder()
                .installationId(INSTALLATION_ID).accountLogin("timiroom").teamId(OTHER_TEAM_ID).build();
        when(githubInstallationRepository.findByInstallationId(INSTALLATION_ID)).thenReturn(Optional.of(installation));

        assertThatThrownBy(() -> service.linkToTeam(TEAM_ID, MEMBER_ID, INSTALLATION_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void linkToTeam_같은_팀에_이미_연결된_경우는_그대로_허용한다() {
        GithubInstallation installation = GithubInstallation.builder()
                .installationId(INSTALLATION_ID).accountLogin("timiroom").teamId(TEAM_ID).build();
        when(githubInstallationRepository.findByInstallationId(INSTALLATION_ID)).thenReturn(Optional.of(installation));

        GithubInstallation result = service.linkToTeam(TEAM_ID, MEMBER_ID, INSTALLATION_ID);

        assertThat(result.getTeamId()).isEqualTo(TEAM_ID);
    }

    @Test
    void getRepositories_다른_팀_소유_installation이면_거부한다() {
        GithubInstallation installation = GithubInstallation.builder()
                .installationId(INSTALLATION_ID).accountLogin("timiroom").teamId(OTHER_TEAM_ID).build();
        when(githubInstallationRepository.findByInstallationId(INSTALLATION_ID)).thenReturn(Optional.of(installation));

        assertThatThrownBy(() -> service.getRepositories(TEAM_ID, MEMBER_ID, INSTALLATION_ID))
                .isInstanceOf(SecurityException.class);
        verify(githubClient, never()).listInstallationRepositories(anyLong());
    }

    @Test
    void getRepositories_같은_팀_소유면_레포목록을_반환한다() {
        GithubInstallation installation = GithubInstallation.builder()
                .installationId(INSTALLATION_ID).accountLogin("timiroom").teamId(TEAM_ID).build();
        when(githubInstallationRepository.findByInstallationId(INSTALLATION_ID)).thenReturn(Optional.of(installation));
        when(githubClient.listInstallationRepositories(INSTALLATION_ID))
                .thenReturn(List.of(new GithubRepoInfo(1L, "timiroom/timiroom-backend", "develop", false)));

        List<GithubRepoInfo> result = service.getRepositories(TEAM_ID, MEMBER_ID, INSTALLATION_ID);

        assertThat(result).hasSize(1);
    }

    @Test
    void unlinkFromTeam_프로젝트에_연결된_레포가_남아있으면_거부한다() {
        GithubInstallation installation = GithubInstallation.builder()
                .installationId(INSTALLATION_ID).accountLogin("timiroom").teamId(TEAM_ID).build();
        GithubRepo repo = GithubRepo.builder().id(3L).installationId(INSTALLATION_ID)
                .githubRepoId(30L).fullName("timiroom/timiroom-backend").build();
        when(githubInstallationRepository.findByInstallationId(INSTALLATION_ID)).thenReturn(Optional.of(installation));
        when(githubRepoRepository.findByInstallationId(INSTALLATION_ID)).thenReturn(List.of(repo));
        when(projectRepoLinkRepository.findByGithubRepoId(repo.getId()))
                .thenReturn(List.of(ProjectRepoLink.builder().projectId(2L).githubRepoId(repo.getId()).build()));

        assertThatThrownBy(() -> service.unlinkFromTeam(TEAM_ID, MEMBER_ID, INSTALLATION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("프로젝트 레포 연결");
        assertThat(installation.getTeamId()).isEqualTo(TEAM_ID);
    }

    @Test
    void unlinkFromTeam_연결된_프로젝트가_없으면_해제한다() {
        GithubInstallation installation = GithubInstallation.builder()
                .installationId(INSTALLATION_ID).accountLogin("timiroom").teamId(TEAM_ID).build();
        when(githubInstallationRepository.findByInstallationId(INSTALLATION_ID)).thenReturn(Optional.of(installation));
        when(githubRepoRepository.findByInstallationId(INSTALLATION_ID)).thenReturn(List.of());

        service.unlinkFromTeam(TEAM_ID, MEMBER_ID, INSTALLATION_ID);

        assertThat(installation.getTeamId()).isNull();
    }

    @Test
    void syncInstallations_원격_설치를_upsert한다() {
        when(githubClient.listAppInstallations())
                .thenReturn(List.of(new GithubInstallationInfo(INSTALLATION_ID, "timiroom", "Organization")));
        when(githubInstallationRepository.findByInstallationId(INSTALLATION_ID)).thenReturn(Optional.empty());

        service.syncInstallations(TEAM_ID, MEMBER_ID);

        verify(teamService).requireOwner(TEAM_ID, MEMBER_ID);
        verify(githubInstallationRepository).save(any(GithubInstallation.class));
    }
}
