package com.timiroom.domain.member.controller;

import com.timiroom.domain.member.entity.Team;
import com.timiroom.domain.member.entity.mapping.TeamMember;
import com.timiroom.domain.member.service.TeamService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    /** 팀 생성 */
    @PostMapping
    public ResponseEntity<Team> create(HttpSession session,
                                       @RequestBody Map<String, String> body) {
        Long memberId = (Long) session.getAttribute("memberId");
        return ResponseEntity.ok(teamService.create(
                memberId, body.get("teamName"), body.get("description")));
    }

    /** 초대 코드로 팀 참여 */
    @PostMapping("/join")
    public ResponseEntity<TeamMember> join(HttpSession session,
                                           @RequestBody Map<String, String> body) {
        Long memberId = (Long) session.getAttribute("memberId");
        return ResponseEntity.ok(teamService.joinByInviteCode(memberId, body.get("inviteCode")));
    }

    /** 내 팀 목록 */
    @GetMapping
    public ResponseEntity<List<Team>> myTeams(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        return ResponseEntity.ok(teamService.getMyTeams(memberId));
    }

    /** 팀 멤버 목록 */
    @GetMapping("/{teamId}/members")
    public ResponseEntity<List<TeamMember>> members(@PathVariable Long teamId) {
        return ResponseEntity.ok(teamService.getMembers(teamId));
    }
}
