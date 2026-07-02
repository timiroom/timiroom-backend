package com.timiroom.domain.project;

import com.timiroom.domain.pipeline.PipelineArtifact;
import com.timiroom.domain.pipeline.PipelineArtifactRepository;
import com.timiroom.domain.pipeline.PipelineExecution;
import com.timiroom.domain.pipeline.PipelineExecutionRepository;
import com.timiroom.domain.requirement.RequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final RequirementRepository requirementRepository;
    private final PipelineExecutionRepository pipelineExecutionRepository;
    private final PipelineArtifactRepository pipelineArtifactRepository;

    @Transactional
    public Project create(Long teamId, Long memberId, String projectName, String description) {
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
    public ProjectMember addMember(Long projectId, Long memberId, ProjectRole role) {
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
    public List<Project> getByTeam(Long teamId) {
        return projectRepository.findByTeamId(teamId);
    }

    @Transactional(readOnly = true)
    public Project getById(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId));
    }

    @Transactional(readOnly = true)
    public List<Project> getMyProjects(Long memberId) {
        List<Long> projectIds = projectMemberRepository.findProjectIdsByMemberId(memberId);
        return projectRepository.findAllById(projectIds);
    }

    @Transactional(readOnly = true)
    public List<ProjectMember> getMembers(Long projectId) {
        return projectMemberRepository.findByProjectId(projectId);
    }

    @Transactional
    public void delete(Long projectId, Long memberId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + projectId));

        if (!projectMemberRepository.existsByProjectIdAndMemberId(projectId, memberId)) {
            throw new SecurityException("프로젝트 삭제 권한이 없습니다");
        }

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

    /** 프로젝트의 최신 완료된 artifact 조회 */
    @Transactional(readOnly = true)
    public Optional<PipelineArtifact> getDocument(Long projectId, PipelineArtifact.ArtifactType type) {
        List<Long> requirementIds = requirementRepository.findRequirementIdsByProjectId(projectId);
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
    public PipelineArtifact saveDocument(Long projectId, PipelineArtifact.ArtifactType type, String content) {
        Optional<PipelineArtifact> existing = getDocument(projectId, type);
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
