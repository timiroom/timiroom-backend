package com.timiroom.domain.team;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;

    @Transactional
    public Team create(Long memberId, String teamName, String description) {
        Team team = Team.builder()
                .teamName(teamName)
                .description(description)
                .inviteCode(generateInviteCode())
                .build();
        Team saved = teamRepository.save(team);

        teamMemberRepository.save(TeamMember.builder()
                .teamId(saved.getTeamId())
                .memberId(memberId)
                .teamRole(TeamRole.OWNER)
                .build());

        return saved;
    }

    @Transactional
    public TeamMember joinByInviteCode(Long memberId, String inviteCode) {
        Team team = teamRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 초대 코드입니다"));

        if (teamMemberRepository.existsByTeamIdAndMemberId(team.getTeamId(), memberId)) {
            throw new IllegalStateException("이미 팀에 속해 있습니다");
        }

        return teamMemberRepository.save(TeamMember.builder()
                .teamId(team.getTeamId())
                .memberId(memberId)
                .teamRole(TeamRole.MEMBER)
                .build());
    }

    @Transactional(readOnly = true)
    public List<Team> getMyTeams(Long memberId) {
        List<Long> teamIds = teamMemberRepository.findTeamIdsByMemberId(memberId);
        return teamRepository.findAllById(teamIds);
    }

    @Transactional(readOnly = true)
    public List<TeamMember> getMembers(Long teamId) {
        return teamMemberRepository.findByTeamId(teamId);
    }

    private String generateInviteCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
