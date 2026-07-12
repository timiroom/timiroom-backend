package com.timiroom.domain.github;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepoLinkRepository extends JpaRepository<ProjectRepoLink, Long> {

    List<ProjectRepoLink> findByProjectId(Long projectId);

    Optional<ProjectRepoLink> findByProjectIdAndGithubRepoId(Long projectId, Long githubRepoId);

    boolean existsByProjectIdAndGithubRepoId(Long projectId, Long githubRepoId);
}
