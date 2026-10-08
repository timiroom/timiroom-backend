package com.timiroom.domain.spec.service;

import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType;
import com.timiroom.domain.pipeline.repository.PipelineExecutionRepository;
import com.timiroom.domain.project.entity.Project;
import com.timiroom.domain.project.enums.ProjectRole;
import com.timiroom.domain.project.repository.ProjectMemberRepository;
import com.timiroom.domain.project.repository.ProjectRepository;
import com.timiroom.domain.requirement.repository.RequirementRepository;
import com.timiroom.domain.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
public class DocumentAccessService {
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final PipelineExecutionRepository executions;
    private final RequirementRepository requirements;
    private final TeamService teams;

    public Project requireRead(Long projectId, Long actorId) {
        if (actorId == null || projectId == null) throw new SecurityException("ACCESS_DENIED");
        var project=projects.findById(projectId).orElseThrow(()->new SecurityException("ACCESS_DENIED"));
        teams.requireMembership(project.getTeamId(),actorId);
        return project;
    }
    public void requireWrite(Long projectId, Long actorId, ArtifactType type) {
        requireRead(projectId,actorId);
        var member=members.findByProjectIdAndMemberId(projectId,actorId)
            .orElseThrow(()->new SecurityException("ACCESS_DENIED"));
        Set<ArtifactType> allowed=switch(member.getProjectRole()) {
            case PM -> EnumSet.allOf(ArtifactType.class);
            case BACKEND -> EnumSet.of(ArtifactType.API_SPEC,ArtifactType.DB_SCHEMA);
            case FRONTEND -> EnumSet.of(ArtifactType.API_SPEC,ArtifactType.FEATURE_LIST);
            case DESIGNER -> EnumSet.of(ArtifactType.PRD,ArtifactType.MARKET_RESEARCH,ArtifactType.FEATURE_LIST);
            case INFRA -> EnumSet.of(ArtifactType.DB_SCHEMA);
        };
        if (!allowed.contains(type)) throw new SecurityException("ACCESS_DENIED");
    }
    public void requirePm(Long projectId,Long actorId) {
        requireRead(projectId,actorId);
        if(members.findByProjectIdAndMemberId(projectId,actorId).map(m->m.getProjectRole()!=ProjectRole.PM).orElse(true))
            throw new SecurityException("ACCESS_DENIED");
    }
    public Long projectIdForExecution(Long executionId) {
        var execution=executions.findById(executionId).orElseThrow(()->new SecurityException("ACCESS_DENIED"));
        if(execution.getRequirementId()==null) throw new SecurityException("ACCESS_DENIED");
        return requirements.findById(execution.getRequirementId()).orElseThrow(()->new SecurityException("ACCESS_DENIED")).getProjectId();
    }
    public void requireArtifactWrite(Long projectId,Long actorId,PipelineArtifact artifact) {
        if(!Objects.equals(projectId,projectIdForExecution(artifact.getExecutionId()))) throw new SecurityException("ACCESS_DENIED");
        requireWrite(projectId,actorId,artifact.getArtifactType());
    }
}
