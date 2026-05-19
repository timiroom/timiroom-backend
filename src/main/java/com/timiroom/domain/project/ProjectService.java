package com.timiroom.domain.project;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;

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
}
