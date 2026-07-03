package com.timiroom.domain.team.dto;

import com.timiroom.domain.team.TeamRole;

import java.time.LocalDateTime;

public record TeamMemberView(
        Long memberId,
        String memberName,
        String email,
        TeamRole teamRole,
        LocalDateTime joinedAt
) {}
