package com.timiroom.domain.spec.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType;
import com.timiroom.domain.spec.dto.*;
import com.timiroom.domain.spec.service.SpecChangeService;
import com.timiroom.domain.integration.service.IntegrationActorResolver;
import com.timiroom.domain.integrationjob.service.*;
import com.timiroom.domain.integrationjob.dto.JobDto;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{project}/spec-changes")
@ConditionalOnProperty(name="integration.enabled",havingValue="true")
public class SpecChangeController {
    private final SpecChangeService changes;
    private final IntegrationTaskService tasks;
    private final IntegrationJobService jobs;
    private final IntegrationActorResolver actors;
    public record Create(UUID snapshotId,List<ArtifactType> targets,String instruction,List<String> constraints,
        Map<ArtifactType,JsonNode> documents,Map<ArtifactType,String> expectedHashes,String idempotencyKey) {}
    public record Revision(int revision) {public Revision { if(revision<=0) throw new IllegalArgumentException("INVALID_INPUT"); }}
    public record Review(int revision,String idempotencyKey) {public Review { if(revision<=0) throw new IllegalArgumentException("INVALID_INPUT"); }}
    @PostMapping public JobDto create(Authentication auth,@PathVariable Long project,@RequestBody Create input) {
        if(input.snapshotId()==null) throw new IllegalArgumentException("INVALID_INPUT");
        var instruction=new ChangeInstruction(input.targets(),input.instruction(),input.constraints());
        return input.documents()==null ? tasks.beginChange(project,actors.memberId(auth),input.snapshotId(),instruction,input.idempotencyKey())
            :tasks.beginManualChange(project,actors.memberId(auth),input.snapshotId(),instruction,input.documents(),input.expectedHashes(),input.idempotencyKey());
    }
    @GetMapping("/{id}") public SpecChangeProposalDto get(Authentication auth,@PathVariable Long project,@PathVariable UUID id) {
        return changes.view(project,actors.memberId(auth),id);
    }
    @GetMapping("/jobs/{id}") public JobDto job(Authentication auth,@PathVariable Long project,@PathVariable UUID id) {
        return jobs.get(project,actors.memberId(auth),id);
    }
    @PostMapping("/{id}/approve") public SpecSnapshotDto approve(Authentication auth,@PathVariable Long project,@PathVariable UUID id,@RequestBody Revision input) {
        return changes.approve(project,actors.memberId(auth),id,input.revision());
    }
    @PostMapping("/{id}/reject") public org.springframework.http.ResponseEntity<Void> reject(Authentication auth,@PathVariable Long project,@PathVariable UUID id,@RequestBody Revision input) {
        changes.reject(project,actors.memberId(auth),id,input.revision());
        return org.springframework.http.ResponseEntity.noContent().build();
    }
    @PostMapping("/{id}/review") public JobDto review(Authentication auth,@PathVariable Long project,@PathVariable UUID id,@RequestBody Review input) {
        return tasks.beginArtifactReview(project,actors.memberId(auth),id,input.revision(),input.idempotencyKey());
    }
}
