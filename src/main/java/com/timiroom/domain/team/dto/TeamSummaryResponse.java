package com.timiroom.domain.team.dto;

import com.timiroom.domain.team.enums.TeamRole;

import java.time.LocalDateTime;

public record TeamSummaryResponse(
        Long teamId,
        String teamName,
        String description,
        String inviteCode,
        String iconUrl,
        TeamRole viewerRole,
        LocalDateTime lastActivityAt
) {}
