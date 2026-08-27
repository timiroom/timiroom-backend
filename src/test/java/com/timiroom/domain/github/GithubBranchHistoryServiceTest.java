package com.timiroom.domain.github;

import com.timiroom.domain.project.service.ProjectService;
import com.timiroom.infra.github.GithubClient;
import com.timiroom.infra.github.dto.GithubBranchInfo;
import com.timiroom.infra.github.dto.GithubCommitInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GithubBranchHistoryServiceTest {

    private static final long PROJECT_ID = 1L;
    private static final long MEMBER_ID = 10L;
    private static final long REPO_ID = 99L;

    @Mock ProjectService projectService;
    @Mock ProjectRepoLinkRepository projectRepoLinkRepository;
    @Mock GithubRepoRepository githubRepoRepository;
    @Mock GithubClient githubClient;
    @InjectMocks GithubBranchHistoryService service;

    private void givenLinkedRepo() {
        when(projectRepoLinkRepository.findByProjectIdAndGithubRepoId(PROJECT_ID, REPO_ID))
                .thenReturn(Optional.of(ProjectRepoLink.builder().projectId(PROJECT_ID).githubRepoId(REPO_ID).build()));
        when(githubRepoRepository.findById(REPO_ID)).thenReturn(Optional.of(GithubRepo.builder()
                .id(REPO_ID).githubRepoId(555L).fullName("timiroom/timiroom-backend")
                .defaultBranch("develop").installationId(146037712L).build()));
    }

    @Test
    void branches_프로젝트에_연결된_레포만_조회한다() {
        givenLinkedRepo();
        when(githubClient.listBranches("timiroom/timiroom-backend", 146037712L))
                .thenReturn(List.of(new GithubBranchInfo("develop", "abc123", false)));

        List<GithubBranchInfo> result = service.getBranches(PROJECT_ID, MEMBER_ID, REPO_ID);

        assertThat(result).extracting(GithubBranchInfo::name).containsExactly("develop");
        verify(projectService).getById(PROJECT_ID, MEMBER_ID);
    }

    @Test
    void commits_선택한_브랜치만_조회한다() {
        givenLinkedRepo();
        when(githubClient.listCommits("timiroom/timiroom-backend", 146037712L, "develop"))
                .thenReturn(List.of(new GithubCommitInfo("abc123", "feat: repo link", "Chunwol", "chunwol",
                        "2026-07-12T10:00:00Z", "https://github.com/timiroom/timiroom-backend/commit/abc123")));

        List<GithubCommitInfo> result = service.getCommits(PROJECT_ID, MEMBER_ID, REPO_ID, "develop");

        assertThat(result).extracting(GithubCommitInfo::sha).containsExactly("abc123");
        verify(githubClient).listCommits("timiroom/timiroom-backend", 146037712L, "develop");
    }

    @Test
    void commits_연결되지_않은_레포는_거부한다() {
        when(projectRepoLinkRepository.findByProjectIdAndGithubRepoId(PROJECT_ID, REPO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCommits(PROJECT_ID, MEMBER_ID, REPO_ID, "develop"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("연결된 레포가 아닙니다");
    }
}
