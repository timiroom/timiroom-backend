package com.timiroom.domain.project;

import com.timiroom.domain.pipeline.PipelineArtifact;
import com.timiroom.domain.pipeline.PipelineArtifact.ArtifactType;
import com.timiroom.domain.pipeline.PipelineArtifactRepository;
import com.timiroom.domain.pipeline.PipelineExecution;
import com.timiroom.domain.pipeline.PipelineExecutionRepository;
import com.timiroom.domain.requirement.RequirementRepository;
import com.timiroom.domain.team.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private static final Map<ProjectRole, Set<ArtifactType>> ROLE_DOCUMENT_PERMISSIONS;

    static {
        ROLE_DOCUMENT_PERMISSIONS = new EnumMap<>(ProjectRole.class);
        ROLE_DOCUMENT_PERMISSIONS.put(ProjectRole.PM,
                EnumSet.allOf(ArtifactType.class));
        ROLE_DOCUMENT_PERMISSIONS.put(ProjectRole.BACKEND,
                EnumSet.of(ArtifactType.DB_SCHEMA, ArtifactType.API_SPEC));
        ROLE_DOCUMENT_PERMISSIONS.put(ProjectRole.FRONTEND,
                EnumSet.of(ArtifactType.API_SPEC, ArtifactType.FEATURE_LIST));
        ROLE_DOCUMENT_PERMISSIONS.put(ProjectRole.DESIGNER,
                EnumSet.of(ArtifactType.PRD, ArtifactType.MARKET_RESEARCH, ArtifactType.FEATURE_LIST));
        ROLE_DOCUMENT_PERMISSIONS.put(ProjectRole.INFRA,
                EnumSet.of(ArtifactType.DB_SCHEMA));
    }

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final RequirementRepository requirementRepository;
    private final PipelineExecutionRepository pipelineExecutionRepository;
    private final PipelineArtifactRepository pipelineArtifactRepository;
    private final TeamService teamService;

    @Transactional
    public Project create(Long teamId, Long memberId, String projectName, String description) {
        teamService.requireMemberOrAbove(teamId, memberId);

        Project project = Project.builder()
                .teamId(teamId)
                .projectName(projectName)
                .description(description)
                .build();
        Project saved = projectRepository.save(project);

        projectMemberRepository.save(ProjectMember.builder()
                .projectId(saved.getProjectId())
                .memberId(memberId)
                .projectRole(ProjectRole.PM)
                .build());

        return saved;
    }

    @Transactional
    public ProjectMember addMember(Long projectId, Long actorMemberId, Long memberId, ProjectRole role) {
        getById(projectId, actorMemberId);
        ProjectMember actor = projectMemberRepository.findByProjectIdAndMemberId(projectId, actorMemberId)
                .orElseThrow(() -> new SecurityException("프로젝트 멤버를 추가할 권한이 없습니다"));
        if (actor.getProjectRole() != ProjectRole.PM) {
            throw new SecurityException("프로젝트 멤버를 추가할 권한이 없습니다");
        }

        if (projectMemberRepository.existsByProjectIdAndMemberId(projectId, memberId)) {
            throw new IllegalStateException("이미 프로젝트에 속해 있습니다");
        }
        return projectMemberRepository.save(ProjectMember.builder()
                .projectId(projectId)
                .memberId(memberId)
                .projectRole(role)
                .build());
    }

    @Transactional(readOnly = true)
    public List<Project> getByTeam(Long teamId, Long memberId) {
        teamService.requireMembership(teamId, memberId);
        return projectRepository.findByTeamId(teamId);
    }

    @Transactional(readOnly = true)
    public Project getById(Long projectId, Long memberId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId));
        teamService.requireMembership(project.getTeamId(), memberId);
        return project;
    }

    @Transactional(readOnly = true)
    public List<Project> getMyProjects(Long memberId) {
        List<Long> projectIds = projectMemberRepository.findProjectIdsByMemberId(memberId);
        return projectRepository.findAllById(projectIds);
    }

    @Transactional(readOnly = true)
    public List<ProjectMember> getMembers(Long projectId, Long memberId) {
        getById(projectId, memberId);
        return projectMemberRepository.findByProjectId(projectId);
    }

    @Transactional
    public void delete(Long projectId, Long memberId) {
        Project project = getById(projectId, memberId);
        teamService.requireOwner(project.getTeamId(), memberId);

        // PipelineArtifact → PipelineExecution → Requirement → ProjectMember → Project 순서로 삭제
        List<Long> requirementIds = requirementRepository.findRequirementIdsByProjectId(projectId);
        if (!requirementIds.isEmpty()) {
            List<Long> executionIds = pipelineExecutionRepository.findByRequirementIdIn(requirementIds)
                    .stream().map(PipelineExecution::getExecutionId).collect(Collectors.toList());
            if (!executionIds.isEmpty()) {
                pipelineArtifactRepository.deleteByExecutionIdIn(executionIds);
            }
            pipelineExecutionRepository.deleteByRequirementIdIn(requirementIds);
            requirementRepository.deleteByProjectId(projectId);
        }

        projectMemberRepository.deleteByProjectId(projectId);
        projectRepository.deleteById(projectId);
    }

    @Transactional
    public Project updateProject(Long projectId, Long memberId, String projectName, String description, String status) {
        Project project = getById(projectId, memberId);
        requireProjectRole(projectId, memberId, ProjectRole.PM, "프로젝트를 수정하려면 PM 권한이 필요합니다");

        project.updateInfo(projectName, description);
        if (status != null && !status.isBlank()) {
            try {
                project.updateStatus(ProjectStatus.valueOf(status.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("올바르지 않은 상태값: " + status);
            }
        }
        return projectRepository.save(project);
    }

    @Transactional
    public void removeMember(Long projectId, Long actorId, Long targetMemberId) {
        getById(projectId, actorId);
        requireProjectRole(projectId, actorId, ProjectRole.PM, "멤버 제거 권한이 없습니다");
        if (actorId.equals(targetMemberId)) {
            throw new IllegalArgumentException("자기 자신을 제거할 수 없습니다");
        }
        ProjectMember target = projectMemberRepository.findByProjectIdAndMemberId(projectId, targetMemberId)
                .orElseThrow(() -> new IllegalArgumentException("해당 멤버가 프로젝트에 없습니다"));
        projectMemberRepository.delete(target);
    }

    @Transactional
    public ProjectMember updateMemberRole(Long projectId, Long actorId, Long targetMemberId, ProjectRole newRole) {
        getById(projectId, actorId);
        requireProjectRole(projectId, actorId, ProjectRole.PM, "역할 변경 권한이 없습니다");
        ProjectMember target = projectMemberRepository.findByProjectIdAndMemberId(projectId, targetMemberId)
                .orElseThrow(() -> new IllegalArgumentException("해당 멤버가 프로젝트에 없습니다"));
        target.updateRole(newRole);
        return projectMemberRepository.save(target);
    }

    private void requireProjectRole(Long projectId, Long memberId, ProjectRole required, String message) {
        ProjectMember actor = projectMemberRepository.findByProjectIdAndMemberId(projectId, memberId)
                .orElseThrow(() -> new SecurityException(message));
        if (actor.getProjectRole() != required) {
            throw new SecurityException(message);
        }
    }

    private void requireDocumentPermission(Long projectId, Long memberId, ArtifactType type) {
        ProjectMember pm = projectMemberRepository.findByProjectIdAndMemberId(projectId, memberId)
                .orElseThrow(() -> new SecurityException("프로젝트 멤버만 문서를 수정할 수 있습니다"));
        Set<ArtifactType> allowed = ROLE_DOCUMENT_PERMISSIONS.getOrDefault(pm.getProjectRole(), EnumSet.noneOf(ArtifactType.class));
        if (!allowed.contains(type)) {
            throw new SecurityException(pm.getProjectRole() + " 역할은 " + type + " 문서를 수정할 수 없습니다");
        }
    }

    /** 프로젝트의 최신 완료된 artifact 조회 */
    @Transactional(readOnly = true)
    public Optional<PipelineArtifact> getDocument(Long projectId, Long memberId, PipelineArtifact.ArtifactType type) {
        Project project = getById(projectId, memberId);
        List<Long> requirementIds = requirementRepository.findRequirementIdsByProjectId(project.getProjectId());
        if (requirementIds.isEmpty()) return Optional.empty();

        List<Long> executionIds = pipelineExecutionRepository
                .findByRequirementIdIn(requirementIds).stream()
                .filter(e -> e.getStatus() == PipelineExecution.ExecutionStatus.COMPLETED)
                .map(PipelineExecution::getExecutionId)
                .collect(Collectors.toList());
        if (executionIds.isEmpty()) return Optional.empty();

        return pipelineArtifactRepository.findByExecutionIdsAndType(executionIds, type)
                .stream().findFirst();
    }

    /** artifact content 수정 (없으면 신규 저장) */
    @Transactional
    public PipelineArtifact saveDocument(Long projectId, Long memberId, PipelineArtifact.ArtifactType type, String content) {
        Project project = getById(projectId, memberId);
        requireDocumentPermission(projectId, memberId, type);

        Optional<PipelineArtifact> existing = getDocument(project.getProjectId(), memberId, type);
        if (existing.isPresent()) {
            PipelineArtifact artifact = existing.get();
            artifact.updateContent(content);
            return pipelineArtifactRepository.save(artifact);
        }
        // 실행 이력이 없는 경우 — 가장 최근 execution에 저장
        List<Long> requirementIds = requirementRepository.findRequirementIdsByProjectId(projectId);
        Long executionId = pipelineExecutionRepository.findByRequirementIdIn(requirementIds)
                .stream().map(PipelineExecution::getExecutionId).findFirst()
                .orElseThrow(() -> new IllegalStateException("파이프라인 실행 이력이 없습니다"));

        return pipelineArtifactRepository.save(PipelineArtifact.builder()
                .executionId(executionId)
                .artifactType(type)
                .content(content)
                .build());
    }
}
