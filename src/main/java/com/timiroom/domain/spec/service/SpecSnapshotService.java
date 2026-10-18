package com.timiroom.domain.spec.service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType;
import com.timiroom.domain.pipeline.repository.PipelineArtifactRepository;
import com.timiroom.domain.pipeline.service.PipelineService;
import com.timiroom.domain.project.repository.ProjectRepository;
import com.timiroom.domain.spec.dto.*;
import com.timiroom.domain.spec.entity.SpecSnapshot;
import com.timiroom.domain.spec.repository.SpecSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class SpecSnapshotService {
    private static final Set<ArtifactType> TYPES=EnumSet.of(ArtifactType.PRD,ArtifactType.API_SPEC,ArtifactType.DB_SCHEMA,ArtifactType.FEATURE_LIST);
    private final ProjectRepository projects;
    private final PipelineService pipeline;
    private final PipelineArtifactRepository artifacts;
    private final SpecSnapshotRepository snapshots;
    private final DocumentAccessService access;
    private final ObjectMapper mapper;
    private final SpecAnchorRecorder anchors;

    @Transactional
    public SpecSnapshotDto publish(Long projectId,Long actorId) { return publish(projectId,actorId,null); }

    /** proposal: 이 발행을 일으킨 승인 변경안(직접 발행이면 null). 명세 지문에 함께 묶인다. */
    @Transactional
    public SpecSnapshotDto publish(Long projectId,Long actorId,ProposalRef proposal) {
        access.requirePm(projectId,actorId);
        projects.findForUpdate(projectId).orElseThrow(()->new SecurityException("ACCESS_DENIED"));
        var selected=pipeline.getLatestArtifactsByProject(projectId).stream()
            .filter(a->TYPES.contains(a.getArtifactType())).sorted(Comparator.comparing(PipelineArtifact::getArtifactId)).toList();
        var documents=new ArrayList<SpecDocumentDto>();
        var present=EnumSet.noneOf(ArtifactType.class);
        for(var candidate:selected) {
            var artifact=artifacts.findForUpdate(candidate.getArtifactId()).orElseThrow(()->new IllegalStateException("SPEC_CONFLICT"));
            if(!present.add(artifact.getArtifactType())) throw new IllegalStateException("SPEC_CONFLICT: duplicate document type");
            if(artifact.getContent()==null || artifact.getContent().isBlank()) throw new IllegalStateException("SPEC_NOT_PUBLISHED: empty document");
            documents.add(new SpecDocumentDto(artifact.getArtifactType(),artifact.getArtifactId(),artifact.getExecutionId(),artifact.getVersion(),DocumentHash.of(artifact.getContent()),artifact.getContent()));
        }
        if(!present.containsAll(EnumSet.of(ArtifactType.PRD,ArtifactType.API_SPEC,ArtifactType.DB_SCHEMA)))
            throw new IllegalStateException("SPEC_NOT_PUBLISHED: required documents missing");
        int revision=snapshots.findFirstByProjectIdOrderByRevisionDesc(projectId).map(s->s.getRevision()+1).orElse(1);
        // 지문 사슬: 지문이 없는 과거 revision을 먼저 채우고(저장 전), 새 revision의 지문을 같은 트랜잭션에 남긴다.
        anchors.ensureProject(projectId);
        try {
            var snapshot=snapshots.save(new SpecSnapshot(projectId,revision,actorId,mapper.writeValueAsString(documents)));
            anchors.record(snapshot,documents,proposal);
            return dto(snapshot,documents);
        } catch(JsonProcessingException e) { throw new IllegalStateException("Cannot serialize snapshot",e); }
    }
    @Transactional(readOnly=true)
    public SpecSnapshotDto get(Long projectId,Long actorId,UUID snapshotId) {
        access.requireRead(projectId,actorId);
        return read(snapshots.findBySnapshotIdAndProjectId(snapshotId,projectId).orElseThrow(()->new IllegalArgumentException("SNAPSHOT_NOT_FOUND")));
    }
    @Transactional(readOnly=true)
    public SpecSnapshotDto latest(Long projectId,Long actorId) {
        access.requireRead(projectId,actorId);
        return read(snapshots.findFirstByProjectIdOrderByRevisionDesc(projectId).orElseThrow(()->new IllegalStateException("SPEC_NOT_PUBLISHED")));
    }
    private SpecSnapshotDto read(SpecSnapshot s) {
        try { return dto(s,mapper.readValue(s.getDocumentsJson(),new TypeReference<List<SpecDocumentDto>>(){})); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Invalid stored snapshot",e); }
    }
    private SpecSnapshotDto dto(SpecSnapshot s,List<SpecDocumentDto> documents) {
        return new SpecSnapshotDto(s.getSnapshotId(),s.getProjectId(),s.getRevision(),s.getPublishedBy(),s.getPublishedAt(),documents);
    }
}
