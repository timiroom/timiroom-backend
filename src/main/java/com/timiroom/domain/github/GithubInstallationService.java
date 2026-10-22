package com.timiroom.domain.github;

import com.timiroom.domain.team.service.TeamService;
import com.timiroom.infra.github.GithubClient;
import com.timiroom.infra.github.dto.GithubInstallationInfo;
import com.timiroom.infra.github.dto.GithubRepoInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * GitHub App 설치 ↔ 팀(워크스페이스) 매핑.
 *
 * GitHub 설치 자체는 "이게 어느 timiroom 워크스페이스 것인지" 알지 못하므로(Setup URL 콜백 미사용),
 * 동기화는 전역으로 발견하고, 팀 연결은 별도 명시적 액션으로 나눈다:
 *   1) syncInstallations() — App 전체 설치를 DB에 upsert (teamId는 건드리지 않음)
 *   2) getUnassigned()      — 아직 어느 팀에도 연결 안 된 설치 후보 (팀 OWNER만 조회)
 *   3) linkToTeam()         — 팀 OWNER가 후보 중 하나를 자기 워크스페이스에 연결
 * 레포 조회(getRepositories)는 installation이 실제로 그 팀 소유인지 검증한 뒤에만 허용한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubInstallationService {

    private final GithubInstallationRepository githubInstallationRepository;
    private final GithubRepoRepository githubRepoRepository;
    private final ProjectRepoLinkRepository projectRepoLinkRepository;
    private final TeamService teamService;
    private final GithubClient githubClient;

    /**
     * GitHub API에서 이 App의 설치 목록을 가져와 DB에 upsert.
     * 이미 팀에 연결된 설치는 teamId를 유지한 채 계정 정보만 갱신한다.
     */
    @Transactional
    public List<GithubInstallationInfo> syncInstallations(Long teamId, Long memberId) {
        teamService.requireOwner(teamId, memberId);
        List<GithubInstallationInfo> remote = githubClient.listAppInstallations();
        for (GithubInstallationInfo info : remote) {
            githubInstallationRepository.findByInstallationId(info.installationId())
                    .ifPresentOrElse(
                            existing -> existing.updateAccount(info.accountLogin(), info.accountType()),
                            () -> githubInstallationRepository.save(GithubInstallation.builder()
                                    .installationId(info.installationId())
                                    .accountLogin(info.accountLogin())
                                    .accountType(info.accountType())
                                    .build()));
        }
        log.info("GitHub 설치 동기화 완료 — 원격 {}건", remote.size());
        return remote;
    }

    /** 이 팀(워크스페이스)에 연결된 설치 목록 */
    @Transactional(readOnly = true)
    public List<GithubInstallation> getByTeam(Long teamId, Long memberId) {
        teamService.requireMembership(teamId, memberId);
        return githubInstallationRepository.findByTeamId(teamId);
    }

    /** 아직 어느 팀에도 연결되지 않은 설치 후보. 실제 연결(linkToTeam)은 팀 OWNER만 할 수 있다. */
    @Transactional(readOnly = true)
    public List<GithubInstallation> getUnassigned(Long teamId, Long memberId) {
        teamService.requireOwner(teamId, memberId);
        return githubInstallationRepository.findByTeamIdIsNull();
    }

    /** 미할당 설치를 이 팀에 연결 (팀 OWNER 전용) */
    @Transactional
    public GithubInstallation linkToTeam(Long teamId, Long memberId, Long installationId) {
        teamService.requireOwner(teamId, memberId);
        GithubInstallation installation = githubInstallationRepository.findByInstallationId(installationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "등록되지 않은 installation입니다. 먼저 동기화를 실행해주세요: " + installationId));
        if (installation.getTeamId() != null && !installation.getTeamId().equals(teamId)) {
            throw new IllegalStateException("이미 다른 워크스페이스에 연결된 설치입니다: " + installationId);
        }
        installation.linkTeam(teamId);
        log.info("GitHub installation {} 을 team {} 에 연결", installationId, teamId);
        return installation;
    }

    /** 팀 연결 해제 (팀 OWNER 전용) */
    @Transactional
    public void unlinkFromTeam(Long teamId, Long memberId, Long installationId) {
        teamService.requireOwner(teamId, memberId);
        GithubInstallation installation = requireOwnedByTeam(teamId, installationId);
        boolean hasLinkedProjects = githubRepoRepository.findByInstallationId(installationId).stream()
                .anyMatch(repo -> !projectRepoLinkRepository.findByGithubRepoId(repo.getId()).isEmpty());
        if (hasLinkedProjects) {
            throw new IllegalStateException("이 설치를 사용하는 프로젝트 레포 연결을 먼저 해제해주세요");
        }
        installation.linkTeam(null);
    }

    /** 설치가 접근 가능한 레포 목록 — installation이 이 팀 소유일 때만 허용 */
    @Transactional(readOnly = true)
    public List<GithubRepoInfo> getRepositories(Long teamId, Long memberId, Long installationId) {
        teamService.requireMembership(teamId, memberId);
        requireOwnedByTeam(teamId, installationId);
        return githubClient.listInstallationRepositories(installationId);
    }

    private GithubInstallation requireOwnedByTeam(Long teamId, Long installationId) {
        GithubInstallation installation = githubInstallationRepository.findByInstallationId(installationId)
                .orElseThrow(() -> new IllegalArgumentException("등록되지 않은 installation입니다: " + installationId));
        if (!teamId.equals(installation.getTeamId())) {
            throw new SecurityException("이 워크스페이스에 연결되지 않은 installation입니다: " + installationId);
        }
        return installation;
    }
}
