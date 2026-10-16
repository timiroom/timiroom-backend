package com.timiroom.domain.commit.repository;

import com.timiroom.domain.commit.entity.Commit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommitRepository extends JpaRepository<Commit, Long> {
    List<Commit> findAllByOrderByCreatedAtDesc();
    List<Commit> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
