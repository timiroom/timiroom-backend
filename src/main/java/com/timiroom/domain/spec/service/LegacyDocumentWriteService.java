package com.timiroom.domain.spec.service;
import com.timiroom.domain.pipeline.service.PipelineService;
import com.timiroom.domain.pipeline.repository.PipelineArtifactRepository;
import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType;
import com.timiroom.domain.project.service.ProjectService;
import com.timiroom.domain.project.repository.ProjectRepository;
import com.timiroom.domain.spec.repository.SpecSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Only legacy browser saves use this guard; reviewed approval uses the batch writer directly. */
@Service @RequiredArgsConstructor
public class LegacyDocumentWriteService {
    private final PipelineService pipeline;
    private final ProjectService projects;
    private final ProjectRepository projectRows;
    private final PipelineArtifactRepository artifacts;
    private final DocumentAccessService access;
    private final SpecSnapshotRepository snapshots;
    @Value("${integration.enabled:false}") private boolean enabled;
    @Transactional public void artifact(Long actor,Long artifactId,String content) {
        var artifact=artifacts.findById(artifactId).orElseThrow(()->new SecurityException("ACCESS_DENIED"));
        Long project=access.projectIdForExecution(artifact.getExecutionId());access.requireArtifactWrite(project,actor,artifact);
        guard(project);pipeline.updateArtifact(actor,artifactId,content);
    }
    @Transactional public PipelineArtifact project(Long project,Long actor,ArtifactType type,String content) {
        access.requireWrite(project,actor,type);guard(project);return projects.saveDocument(project,actor,type,content);
    }
    private void guard(Long project) {
        if(!enabled) return;
        // Lock project before artifact writes, matching publication and approval lock order.
        projectRows.findForUpdate(project).orElseThrow(()->new SecurityException("ACCESS_DENIED"));
        if(snapshots.findFirstByProjectIdOrderByRevisionDesc(project).isPresent()) throw new IllegalStateException("SPEC_CHANGE_REQUIRED");
    }
}
