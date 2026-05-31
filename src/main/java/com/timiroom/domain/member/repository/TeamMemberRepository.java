package com.timiroom.domain.member.repository;

import com.timiroom.domain.member.entity.mapping.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {
    List<TeamMember> findByMemberId(Long memberId);
    List<TeamMember> findByTeamId(Long teamId);
    Optional<TeamMember> findByTeamIdAndMemberId(Long teamId, Long memberId);
    boolean existsByTeamIdAndMemberId(Long teamId, Long memberId);

    @Query("SELECT tm.teamId FROM TeamMember tm WHERE tm.memberId = :memberId")
    List<Long> findTeamIdsByMemberId(Long memberId);
}
