package com.timiroom.domain.github;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GithubInstallationRepository extends JpaRepository<GithubInstallation, Long> {

    Optional<GithubInstallation> findByInstallationId(Long installationId);

    List<GithubInstallation> findByTeamId(Long teamId);

    List<GithubInstallation> findByTeamIdIsNull();
}
