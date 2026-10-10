package com.timiroom.domain.spec.service;

import com.timiroom.domain.pipeline.entity.ArtifactRevision;
import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import com.timiroom.domain.pipeline.repository.ArtifactRevisionRepository;
import com.timiroom.domain.pipeline.repository.PipelineArtifactRepository;
import com.timiroom.domain.spec.dto.ArtifactWriteCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class ArtifactWriteService {
    private final PipelineArtifactRepository artifacts;
    private final ArtifactRevisionRepository revisions;
    private final DocumentAccessService access;

    @Transactional
    public PipelineArtifact write(Long projectId,Long actorId,Long artifactId,int expectedVersion,String content) {
        return writeBatch(projectId,actorId,List.of(new ArtifactWriteCommand(artifactId,expectedVersion,null,content))).getFirst();
    }
    @Transactional
    public PipelineArtifact writeCurrent(Long actorId,Long artifactId,String content) {
        var artifact=load(artifactId);
        Long projectId=access.projectIdForExecution(artifact.getExecutionId());
        access.requireArtifactWrite(projectId,actorId,artifact);
        if(content==null) throw new IllegalArgumentException("Content is required");
        return apply(artifact,content);
    }
    @Transactional
    public List<PipelineArtifact> writeBatch(Long projectId,Long actorId,List<ArtifactWriteCommand> commands) {
        if(commands==null || commands.isEmpty()) throw new IllegalArgumentException("Writes are required");
        if(commands.stream().anyMatch(c->c==null || c.artifactId()==null || c.content()==null))
            throw new IllegalArgumentException("Artifact and content are required");
        if(commands.stream().map(ArtifactWriteCommand::artifactId).distinct().count()!=commands.size())
            throw new IllegalArgumentException("Duplicate artifact");
        var ordered=commands.stream().sorted(Comparator.comparing(ArtifactWriteCommand::artifactId)).toList();
        var locked=new LinkedHashMap<Long,PipelineArtifact>();
        for(var command:ordered) {
            var artifact=load(command.artifactId());
            access.requireArtifactWrite(projectId,actorId,artifact);
            if(artifact.getVersion()!=command.expectedVersion() || (command.expectedHash()!=null && !command.expectedHash().equals(DocumentHash.of(artifact.getContent()))))
                throw new IllegalStateException("SPEC_CONFLICT");
            locked.put(command.artifactId(),artifact);
        }
        // Validate the entire bundle before making any changes.
        for(var command:ordered) apply(locked.get(command.artifactId()),command.content());
        return commands.stream().map(c->locked.get(c.artifactId())).toList();
    }
    private PipelineArtifact load(Long id) {
        return artifacts.findForUpdate(id).orElseThrow(()->new IllegalArgumentException("Artifact not found"));
    }
    private PipelineArtifact apply(PipelineArtifact artifact,String content) {
        if(content.equals(artifact.getContent())) return artifact;
        revisions.save(ArtifactRevision.builder().artifactId(artifact.getArtifactId()).version(artifact.getVersion()).content(artifact.getContent()).build());
        artifact.updateContent(content);
        return artifacts.save(artifact);
    }
}
