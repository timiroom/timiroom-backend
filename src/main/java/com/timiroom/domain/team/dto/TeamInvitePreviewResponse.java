package com.timiroom.domain.team.dto;

public record TeamInvitePreviewResponse(
        Long teamId,
        String teamName,
        String description,
        String inviteCode,
        String ownerName,
        long memberCount
) {}
