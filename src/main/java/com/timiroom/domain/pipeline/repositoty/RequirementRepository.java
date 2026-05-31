package com.timiroom.domain.pipeline.repositoty;

import com.timiroom.domain.pipeline.entity.Requirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequirementRepository extends JpaRepository<Requirement, Long> {
    List<Requirement> findByProjectIdOrderByCreatedAtDesc(Long projectId);
    List<Requirement> findByMemberIdOrderByCreatedAtDesc(Long memberId);

    @org.springframework.data.jpa.repository.Query("SELECT r.requirementId FROM Requirement r WHERE r.projectId = :projectId")
    List<Long> findRequirementIdsByProjectId(Long projectId);

    void deleteByProjectId(Long projectId);
}
