package com.timiroom.domain.team;

import com.timiroom.domain.team.dto.TeamMemberView;
import com.timiroom.domain.team.dto.TeamInvitePreviewResponse;
import com.timiroom.domain.team.dto.TeamJoinResponse;
import com.timiroom.domain.team.dto.TeamSummaryResponse;
import com.timiroom.domain.team.dto.TeamWorkspaceResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
    public ResponseEntity<?> create(HttpSession session,
                                    @RequestBody Map<String, String> body) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            Team created = teamService.create(memberId, body.get("teamName"), body.get("description"));
            TeamSummaryResponse summary = teamService.getTeamSummary(created.getTeamId(), memberId);
            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        }
    }

    /** 초대 코드로 팀 참여 */
    @PostMapping("/join")
    public ResponseEntity<?> join(HttpSession session,
                                  @RequestBody Map<String, String> body) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            TeamMember joined = teamService.joinByInviteCode(memberId, body.get("inviteCode"));
            return ResponseEntity.ok(new TeamJoinResponse(joined.getTeamId()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        }
    }

    /** 초대 코드로 워크스페이스 미리보기 */
    @GetMapping("/invite/{inviteCode}")
    public ResponseEntity<?> invitePreview(@PathVariable String inviteCode) {
        try {
            TeamInvitePreviewResponse preview = teamService.getInvitePreview(inviteCode);
            return ResponseEntity.ok(preview);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    /** 내 팀 목록 */
    @GetMapping
    public ResponseEntity<?> myTeams(HttpSession session) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();
        List<TeamSummaryResponse> teams = teamService.getMyTeams(memberId);
        return ResponseEntity.ok(teams);
    }

    /** 워크스페이스 상세 */
    @GetMapping("/{teamId}/workspace")
    public ResponseEntity<?> workspace(HttpSession session,
                                       @PathVariable Long teamId) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            TeamWorkspaceResponse workspace = teamService.getWorkspace(teamId, memberId);
            return ResponseEntity.ok(workspace);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 팀 멤버 목록 */
    @GetMapping("/{teamId}/members")
    public ResponseEntity<?> members(HttpSession session,
                                     @PathVariable Long teamId) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            List<TeamMemberView> members = teamService.getMembers(teamId, memberId);
            return ResponseEntity.ok(members);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 팀 정보 수정 */
    @PatchMapping("/{teamId}")
    public ResponseEntity<?> update(HttpSession session,
                                    @PathVariable Long teamId,
                                    @RequestBody Map<String, String> body) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            teamService.updateTeam(
                    teamId,
                    memberId,
                    body.get("teamName"),
                    body.get("description")
            );
            return ResponseEntity.ok(teamService.getTeamSummary(teamId, memberId));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 초대 코드 재발급 */
    @PatchMapping("/{teamId}/invite-code")
    public ResponseEntity<?> regenerateInviteCode(HttpSession session,
                                                  @PathVariable Long teamId) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            teamService.regenerateInviteCode(teamId, memberId);
            return ResponseEntity.ok(teamService.getTeamSummary(teamId, memberId));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 오너 권한 이전 */
    @PatchMapping("/{teamId}/owner")
    public ResponseEntity<?> transferOwnership(HttpSession session,
                                               @PathVariable Long teamId,
                                               @RequestBody Map<String, String> body) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            String targetMemberValue = body.get("memberId");
            if (targetMemberValue == null || targetMemberValue.isBlank()) {
                return badRequest("memberId가 필요합니다");
            }
            Long targetMemberId = Long.valueOf(targetMemberValue);
            return ResponseEntity.ok(teamService.transferOwnership(teamId, memberId, targetMemberId));
        } catch (NumberFormatException e) {
            return badRequest("memberId가 올바르지 않습니다");
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 멤버 역할 변경 */
    @PatchMapping("/{teamId}/members/{targetMemberId}")
    public ResponseEntity<?> updateMemberRole(HttpSession session,
                                              @PathVariable Long teamId,
                                              @PathVariable Long targetMemberId,
                                              @RequestBody Map<String, String> body) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            String roleValue = body.get("role");
            if (roleValue == null || roleValue.isBlank()) return badRequest("role이 필요합니다");
            TeamRole newRole = TeamRole.valueOf(roleValue.toUpperCase());
            return ResponseEntity.ok(teamService.updateMemberRole(teamId, memberId, targetMemberId, newRole));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 멤버 강퇴 */
    @DeleteMapping("/{teamId}/members/{memberId}")
    public ResponseEntity<?> removeMember(HttpSession session,
                                          @PathVariable Long teamId,
                                          @PathVariable Long memberId) {
        Long requesterId = getMemberId(session);
        if (requesterId == null) return unauthorized();

        try {
            teamService.removeMember(teamId, requesterId, memberId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 워크스페이스 나가기 */
    @PostMapping("/{teamId}/leave")
    public ResponseEntity<?> leave(HttpSession session,
                                   @PathVariable Long teamId) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            teamService.leaveTeam(teamId, memberId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    /** 워크스페이스 삭제 */
    @DeleteMapping("/{teamId}")
    public ResponseEntity<?> delete(HttpSession session,
                                    @PathVariable Long teamId) {
        Long memberId = getMemberId(session);
        if (memberId == null) return unauthorized();

        try {
            teamService.deleteTeam(teamId, memberId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        }
    }

    private Long getMemberId(HttpSession session) {
        return (Long) session.getAttribute("memberId");
    }

    private ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Unauthorized"));
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private ResponseEntity<Map<String, String>> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", message));
    }

    private ResponseEntity<Map<String, String>> forbidden(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", message));
    }
}
