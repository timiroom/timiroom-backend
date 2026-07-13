package com.timiroom.domain.member.controller;

import com.timiroom.domain.member.dto.TeamReqDTO;
import com.timiroom.domain.member.entity.Team;
import com.timiroom.domain.member.entity.mapping.TeamMember;
import com.timiroom.domain.member.exception.code.MemberSuccessCode;
import com.timiroom.domain.member.service.TeamService;
import com.timiroom.global.ApiResponse;
import com.timiroom.global.apiPayload.code.BaseSuccessCode;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    /** 팀 생성 */
    @PostMapping
    public ApiResponse<Team> create(HttpSession session,
                                    @RequestBody TeamReqDTO.Create request) {
        BaseSuccessCode code = MemberSuccessCode.OK;
        return ApiResponse.onSuccess(code, teamService.create(session, request));
    }

    /** 초대 코드로 팀 참여 */
    @PostMapping("/join")
    public ApiResponse<TeamMember> join(HttpSession session,
                                        @RequestParam String inviteCode) {
        BaseSuccessCode code = MemberSuccessCode.OK;
        return ApiResponse.onSuccess(code, teamService.joinByInviteCode(session, inviteCode));
    }

    /** 내 팀 목록 */
    @GetMapping
    public ApiResponse<List<Team>> myTeams(HttpSession session) {
        BaseSuccessCode code = MemberSuccessCode.OK;
        return ApiResponse.onSuccess(code, teamService.getMyTeams(session));
    }

    /** 팀 멤버 목록 */
    @GetMapping("/{teamId}/members")
    public ApiResponse<List<TeamMember>> members(@PathVariable Long teamId) {
        BaseSuccessCode code = MemberSuccessCode.OK;
        return ApiResponse.onSuccess(code, teamService.getMembers(teamId));
    }
}