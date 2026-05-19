package com.timiroom.domain.requirement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RequirementRepository extends JpaRepository<Requirement, Long> {
    List<Requirement> findByProjectIdOrderByCreatedAtDesc(Long projectId);
    List<Requirement> findByMemberIdOrderByCreatedAtDesc(Long memberId);
}
