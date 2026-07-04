package com.timiroom.domain.team.dto;

import com.timiroom.domain.team.TeamRole;

public record TeamSummaryResponse(
        Long teamId,
        String teamName,
        String description,
        String inviteCode,
        String iconUrl,
        TeamRole viewerRole
) {}
