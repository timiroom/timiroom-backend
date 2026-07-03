package com.timiroom.domain.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {
    List<ProjectMember> findByProjectId(Long projectId);
    Optional<ProjectMember> findByProjectIdAndMemberId(Long projectId, Long memberId);
    boolean existsByProjectIdAndMemberId(Long projectId, Long memberId);

    @Query("SELECT pm.projectId FROM ProjectMember pm WHERE pm.memberId = :memberId")
    List<Long> findProjectIdsByMemberId(Long memberId);

    void deleteByProjectId(Long projectId);
    void deleteByMemberIdAndProjectIdIn(Long memberId, List<Long> projectIds);
}
