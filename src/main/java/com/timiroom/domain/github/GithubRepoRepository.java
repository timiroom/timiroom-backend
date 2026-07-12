package com.timiroom.domain.github;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GithubRepoRepository extends JpaRepository<GithubRepo, Long> {

    Optional<GithubRepo> findByGithubRepoId(Long githubRepoId);

    List<GithubRepo> findByIdIn(List<Long> ids);
}
