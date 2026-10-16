package com.timiroom.domain.team.service;

import com.timiroom.domain.member.entity.Member;
import com.timiroom.domain.member.repository.MemberRepository;
import com.timiroom.domain.pipeline.repository.PipelineArtifactRepository;
import com.timiroom.domain.pipeline.entity.PipelineExecution;
import com.timiroom.domain.pipeline.repository.PipelineExecutionRepository;
import com.timiroom.domain.project.entity.Project;
import com.timiroom.domain.project.entity.mapping.ProjectMember;
import com.timiroom.domain.project.repository.ProjectMemberRepository;
import com.timiroom.domain.project.enums.ProjectRole;
import com.timiroom.domain.project.repository.ProjectRepository;
import com.timiroom.domain.requirement.repository.RequirementRepository;
import com.timiroom.domain.team.dto.TeamInvitePreviewResponse;
import com.timiroom.domain.team.dto.TeamMemberView;
import com.timiroom.domain.team.dto.TeamSummaryResponse;
import com.timiroom.domain.team.dto.TeamWorkspaceResponse;
import com.timiroom.domain.team.entity.Team;
import com.timiroom.domain.team.entity.mapping.TeamMember;
import com.timiroom.domain.team.enums.TeamRole;
import com.timiroom.domain.team.repository.TeamMemberRepository;
import com.timiroom.domain.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final MemberRepository memberRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final RequirementRepository requirementRepository;
    private final PipelineExecutionRepository pipelineExecutionRepository;
    private final PipelineArtifactRepository pipelineArtifactRepository;

    @Transactional
    public Team create(Long memberId, String teamName, String description) {
        String normalizedTeamName = normalizeTeamName(teamName);
        String inviteCode = generateUniqueInviteCode();
        Team team = Team.builder()
                .teamName(normalizedTeamName)
                .description(normalizeDescription(description))
                .inviteCode(inviteCode)
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
        String normalizedInviteCode = normalizeInviteCode(inviteCode);
        Team team = teamRepository.findByInviteCode(normalizedInviteCode)
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
    public List<TeamSummaryResponse> getMyTeams(Long memberId) {
        List<TeamMember> memberships = teamMemberRepository.findByMemberIdOrderByJoinedAtAsc(memberId);
        List<Long> teamIds = memberships.stream()
                .map(TeamMember::getTeamId)
                .toList();

        Map<Long, Team> teamMap = teamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(Team::getTeamId, team -> team));

        return memberships.stream()
                .map(membership -> {
                    Team team = teamMap.get(membership.getTeamId());
                    if (team == null) {
                        return null;
                    }
                    return toTeamSummary(team, membership.getTeamRole());
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(
                        TeamSummaryResponse::lastActivityAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public TeamSummaryResponse getTeamSummary(Long teamId, Long memberId) {
        Team team = requireTeam(teamId);
        TeamMember membership = requireMembership(teamId, memberId);
        return toTeamSummary(team, membership.getTeamRole());
    }

    @Transactional(readOnly = true)
    public TeamWorkspaceResponse getWorkspace(Long teamId, Long memberId) {
        Team team = requireTeam(teamId);
        TeamMember viewerMembership = requireMembership(teamId, memberId);
        List<TeamMemberView> members = loadTeamMembers(teamId);
        Long ownerMemberId = members.stream()
                .filter(member -> member.teamRole() == TeamRole.OWNER)
                .map(TeamMemberView::memberId)
                .findFirst()
                .orElse(null);

        return new TeamWorkspaceResponse(
                toTeamSummary(team, viewerMembership.getTeamRole()),
                members,
                ownerMemberId,
                viewerMembership.getTeamRole()
        );
    }

    @Transactional(readOnly = true)
    public TeamInvitePreviewResponse getInvitePreview(String inviteCode) {
        String normalizedInviteCode = normalizeInviteCode(inviteCode);
        Team team = teamRepository.findByInviteCode(normalizedInviteCode)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 초대 코드입니다"));

        List<TeamMemberView> members = loadTeamMembers(team.getTeamId());
        String ownerName = members.stream()
                .filter(member -> member.teamRole() == TeamRole.OWNER)
                .map(TeamMemberView::memberName)
                .findFirst()
                .orElse("알 수 없음");

        return new TeamInvitePreviewResponse(
                team.getTeamId(),
                team.getTeamName(),
                team.getDescription(),
                team.getInviteCode(),
                ownerName,
                members.size()
        );
    }

    @Transactional(readOnly = true)
    public List<TeamMemberView> getMembers(Long teamId, Long memberId) {
        requireMembership(teamId, memberId);
        return loadTeamMembers(teamId);
    }

    @Transactional
    public Team updateTeam(Long teamId, Long memberId, String teamName, String description) {
        requireOwner(teamId, memberId);

        Team team = requireTeam(teamId);
        team.updateInfo(normalizeTeamName(teamName), normalizeDescription(description));
        return teamRepository.save(team);
    }

    @Transactional
    public Team updateTeamIcon(Long teamId, Long memberId, String iconUrl) {
        requireOwner(teamId, memberId);
        Team team = requireTeam(teamId);
        team.updateIconUrl(iconUrl);
        return teamRepository.save(team);
    }

    @Transactional
    public Team regenerateInviteCode(Long teamId, Long memberId) {
        requireOwner(teamId, memberId);

        Team team = requireTeam(teamId);
        team.updateInviteCode(generateUniqueInviteCode());
        return teamRepository.save(team);
    }

    @Transactional
    public TeamMember transferOwnership(Long teamId, Long memberId, Long targetMemberId) {
        requireOwner(teamId, memberId);
        if (Objects.equals(memberId, targetMemberId)) {
            throw new IllegalArgumentException("자기 자신에게는 권한을 이전할 수 없습니다");
        }

        TeamMember currentOwner = requireMembership(teamId, memberId);
        TeamMember targetMember = requireMembership(teamId, targetMemberId);

        if (targetMember.getTeamRole() == TeamRole.OWNER) {
            throw new IllegalStateException("이미 오너입니다");
        }

        currentOwner.updateRole(TeamRole.MEMBER);
        targetMember.updateRole(TeamRole.OWNER);
        teamMemberRepository.saveAll(List.of(currentOwner, targetMember));
        return targetMember;
    }

    @Transactional
    public void removeMember(Long teamId, Long memberId, Long targetMemberId) {
        requireOwner(teamId, memberId);
        if (Objects.equals(memberId, targetMemberId)) {
            throw new IllegalArgumentException("오너는 자기 자신을 제거할 수 없습니다");
        }

        TeamMember targetMember = requireMembership(teamId, targetMemberId);
        if (targetMember.getTeamRole() == TeamRole.OWNER) {
            throw new IllegalStateException("오너는 제거할 수 없습니다");
        }

        detachProjectMemberships(teamId, targetMemberId);
        teamMemberRepository.delete(targetMember);
    }

    @Transactional
    public void leaveTeam(Long teamId, Long memberId) {
        TeamMember membership = requireMembership(teamId, memberId);
        if (membership.getTeamRole() == TeamRole.OWNER) {
            throw new IllegalStateException("오너는 먼저 권한을 이전해야 합니다");
        }

        detachProjectMemberships(teamId, memberId);
        teamMemberRepository.delete(membership);
    }

    @Transactional
    public void deleteTeam(Long teamId, Long memberId) {
        requireOwner(teamId, memberId);
        requireTeam(teamId);

        List<Project> projects = projectRepository.findByTeamId(teamId);
        for (Project project : projects) {
            deleteProjectResources(project.getProjectId());
            projectMemberRepository.deleteByProjectId(project.getProjectId());
        }

        if (!projects.isEmpty()) {
            projectRepository.deleteAll(projects);
        }

        teamMemberRepository.deleteByTeamId(teamId);
        teamRepository.deleteById(teamId);
    }

    public TeamMember requireMembership(Long teamId, Long memberId) {
        requireTeam(teamId);
        return teamMemberRepository.findByTeamIdAndMemberId(teamId, memberId)
                .orElseThrow(() -> new SecurityException("팀 접근 권한이 없습니다"));
    }

    public TeamMember requireOwner(Long teamId, Long memberId) {
        TeamMember membership = requireMembership(teamId, memberId);
        if (membership.getTeamRole() != TeamRole.OWNER) {
            throw new SecurityException("오너 권한이 필요합니다");
        }
        return membership;
    }

    public TeamMember requireMemberOrAbove(Long teamId, Long memberId) {
        TeamMember membership = requireMembership(teamId, memberId);
        if (membership.getTeamRole() == TeamRole.GUEST) {
            throw new SecurityException("멤버 이상 권한이 필요합니다");
        }
        return membership;
    }

    @Transactional
    public TeamMember updateMemberRole(Long teamId, Long actorId, Long targetMemberId, TeamRole newRole) {
        requireOwner(teamId, actorId);
        if (newRole == TeamRole.OWNER) {
            throw new IllegalArgumentException("소유자 권한은 이전(transfer)으로만 변경할 수 있습니다");
        }
        TeamMember target = requireMembership(teamId, targetMemberId);
        if (target.getTeamRole() == TeamRole.OWNER) {
            throw new SecurityException("소유자의 역할은 변경할 수 없습니다");
        }
        target.updateRole(newRole);
        return teamMemberRepository.save(target);
    }

    private Team requireTeam(Long teamId) {
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new IllegalArgumentException("팀을 찾을 수 없습니다: " + teamId));
    }

    private List<TeamMemberView> loadTeamMembers(Long teamId) {
        List<TeamMember> teamMembers = teamMemberRepository.findByTeamIdOrderByJoinedAtAsc(teamId);
        if (teamMembers.isEmpty()) {
            return List.of();
        }

        Map<Long, Member> memberMap = memberRepository.findAllById(
                teamMembers.stream().map(TeamMember::getMemberId).toList()
        ).stream().collect(Collectors.toMap(Member::getMemberId, member -> member));

        return teamMembers.stream()
                .sorted(Comparator
                        .comparing((TeamMember teamMember) -> teamMember.getTeamRole() != TeamRole.OWNER)
                        .thenComparing(TeamMember::getJoinedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(teamMember -> {
                    Member member = memberMap.get(teamMember.getMemberId());
                    return new TeamMemberView(
                            teamMember.getMemberId(),
                            member != null ? member.getDisplayName() : "알 수 없음",
                            member != null ? member.getEmail() : "",
                            member != null ? member.getGithubLogin() : null,
                            teamMember.getTeamRole(),
                            teamMember.getJoinedAt()
                    );
                })
                .toList();
    }

    private void detachProjectMemberships(Long teamId, Long memberId) {
        List<Project> projects = projectRepository.findByTeamId(teamId);
        Long ownerMemberId = findOwnerMemberId(teamId);

        for (Project project : projects) {
            List<ProjectMember> projectMembers = projectMemberRepository.findByProjectId(project.getProjectId());
            boolean isProjectMember = projectMembers.stream()
                    .anyMatch(projectMember -> Objects.equals(projectMember.getMemberId(), memberId));
            if (!isProjectMember) {
                continue;
            }

            List<ProjectMember> remainingMembers = projectMembers.stream()
                    .filter(projectMember -> !Objects.equals(projectMember.getMemberId(), memberId))
                    .toList();
            ensureProjectManager(project.getProjectId(), remainingMembers, ownerMemberId, memberId);
        }

        List<Long> projectIds = projects.stream()
                .map(Project::getProjectId)
                .toList();
        if (!projectIds.isEmpty()) {
            projectMemberRepository.deleteByMemberIdAndProjectIdIn(memberId, projectIds);
        }
    }

    private void ensureProjectManager(Long projectId,
                                      List<ProjectMember> remainingMembers,
                                      Long ownerMemberId,
                                      Long removedMemberId) {
        if (remainingMembers.isEmpty()) {
            if (ownerMemberId != null && !Objects.equals(ownerMemberId, removedMemberId)) {
                projectMemberRepository.save(ProjectMember.builder()
                        .projectId(projectId)
                        .memberId(ownerMemberId)
                        .projectRole(ProjectRole.PM)
                        .build());
            }
            return;
        }

        boolean hasProjectManager = remainingMembers.stream()
                .anyMatch(projectMember -> projectMember.getProjectRole() == ProjectRole.PM);
        if (hasProjectManager) {
            return;
        }

        ProjectMember promotedMember = remainingMembers.get(0);
        promotedMember.updateRole(ProjectRole.PM);
        projectMemberRepository.save(promotedMember);
    }

    private void deleteProjectResources(Long projectId) {
        List<Long> requirementIds = requirementRepository.findRequirementIdsByProjectId(projectId);
        if (!requirementIds.isEmpty()) {
            List<Long> executionIds = pipelineExecutionRepository.findByRequirementIdIn(requirementIds)
                    .stream()
                    .map(PipelineExecution::getExecutionId)
                    .toList();
            if (!executionIds.isEmpty()) {
                pipelineArtifactRepository.deleteByExecutionIdIn(executionIds);
            }
            pipelineExecutionRepository.deleteByRequirementIdIn(requirementIds);
            requirementRepository.deleteByProjectId(projectId);
        }
    }

    private String generateUniqueInviteCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String inviteCode = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            if (!teamRepository.existsByInviteCode(inviteCode)) {
                return inviteCode;
            }
        }

        throw new IllegalStateException("초대 코드 생성에 실패했습니다");
    }

    private String normalizeInviteCode(String inviteCode) {
        if (inviteCode == null) {
            throw new IllegalArgumentException("초대 코드가 필요합니다");
        }

        String normalized = inviteCode.trim().toUpperCase();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("초대 코드가 필요합니다");
        }

        return normalized;
    }

    private String normalizeTeamName(String teamName) {
        if (teamName == null) {
            throw new IllegalArgumentException("팀 이름이 필요합니다");
        }

        String normalized = teamName.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("팀 이름이 필요합니다");
        }

        return normalized;
    }

    private String normalizeDescription(String description) {
        return description == null ? "" : description.trim();
    }

    private Long findOwnerMemberId(Long teamId) {
        return teamMemberRepository.findByTeamIdOrderByJoinedAtAsc(teamId).stream()
                .filter(teamMember -> teamMember.getTeamRole() == TeamRole.OWNER)
                .map(TeamMember::getMemberId)
                .findFirst()
                .orElse(null);
    }

    private TeamSummaryResponse toTeamSummary(Team team, TeamRole viewerRole) {
        return new TeamSummaryResponse(
                team.getTeamId(),
                team.getTeamName(),
                team.getDescription(),
                viewerRole == TeamRole.OWNER ? team.getInviteCode() : null,
                team.getIconUrl(),
                viewerRole,
                resolveLastActivityAt(team)
        );
    }

    /**
     * 워크스페이스의 "최신 대화" 정렬 기준 시각.
     * 팀 소속 프로젝트 중 가장 최근에 갱신된 시각(AI 파이프라인 대화로 생성/수정됨)을 사용하고,
     * 프로젝트가 하나도 없으면 팀 생성 시각으로 대체한다.
     */
    private LocalDateTime resolveLastActivityAt(Team team) {
        return projectRepository.findByTeamId(team.getTeamId()).stream()
                .map(Project::getUpdatedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(team.getCreatedAt());
    }
}
