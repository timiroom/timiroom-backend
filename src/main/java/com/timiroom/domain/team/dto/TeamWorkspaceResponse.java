package com.timiroom.domain.team.dto;

import com.timiroom.domain.team.enums.TeamRole;

import java.util.List;

public record TeamWorkspaceResponse(
        TeamSummaryResponse team,
        List<TeamMemberView> members,
        Long ownerMemberId,
        TeamRole viewerRole
) {}
