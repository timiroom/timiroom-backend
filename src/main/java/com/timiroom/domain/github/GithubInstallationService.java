package com.timiroom.domain.github;

import com.timiroom.infra.github.GithubClient;
import com.timiroom.infra.github.dto.GithubInstallationInfo;
import com.timiroom.infra.github.dto.GithubRepoInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GithubInstallationService {

    private final GithubInstallationRepository githubInstallationRepository;
    private final GithubClient githubClient;

    /**
     * GitHub API에서 이 App의 설치 목록을 가져와 DB에 upsert.
     * Setup URL 콜백 없이도 설치를 발견할 수 있는 기본 경로.
     */
    @Transactional
    public List<GithubInstallation> syncInstallations() {
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
        return githubInstallationRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<GithubInstallation> getAll() {
        return githubInstallationRepository.findAll();
    }

    /** 설치가 접근 가능한 레포 목록 (DB에 등록된 설치만 허용) */
    public List<GithubRepoInfo> getRepositories(Long installationId) {
        githubInstallationRepository.findByInstallationId(installationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "등록되지 않은 installation입니다. 먼저 동기화를 실행해주세요: " + installationId));
        return githubClient.listInstallationRepositories(installationId);
    }
}
