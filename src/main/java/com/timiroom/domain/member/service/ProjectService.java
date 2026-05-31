package com.timiroom.domain.member.service;

import com.timiroom.domain.member.entity.Project;
import com.timiroom.domain.member.entity.mapping.ProjectMember;
import com.timiroom.domain.member.enums.ProjectRole;
import com.timiroom.domain.member.repository.ProjectMemberRepository;
import com.timiroom.domain.member.repository.ProjectRepository;
import com.timiroom.domain.pipeline.repositoty.PipelineArtifactRepository;
import com.timiroom.domain.pipeline.entity.PipelineExecution;
import com.timiroom.domain.pipeline.repositoty.PipelineExecutionRepository;
import com.timiroom.domain.pipeline.repositoty.RequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
}
