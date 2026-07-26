package com.timiroom.domain.github;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;

public interface GithubPullRequestReviewRecordRepository extends JpaRepository<GithubPullRequestReviewRecord, Long> {
    Optional<GithubPullRequestReviewRecord> findByProjectIdAndGithubRepoIdAndPullNumber(
            Long projectId, Long githubRepoId, Integer pullNumber);

    Optional<GithubPullRequestReviewRecord> findFirstByProjectIdAndGithubRepoIdInOrderByUpdatedAtDesc(
            Long projectId, Collection<Long> githubRepoIds);
}
