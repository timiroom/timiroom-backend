package com.timiroom.domain.commit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommitRepository extends JpaRepository<Commit, Long> {
    List<Commit> findAllByOrderByCreatedAtDesc();
    List<Commit> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
