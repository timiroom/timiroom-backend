package com.timiroom.domain.integrationjob.service;
import com.fasterxml.jackson.databind.*;
import com.timiroom.domain.integrationjob.dto.JobDto;
import com.timiroom.domain.integrationjob.entity.IntegrationJob;
import com.timiroom.domain.integrationjob.repository.IntegrationJobRepository;
import com.timiroom.domain.project.repository.ProjectRepository;
import com.timiroom.domain.spec.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class IntegrationJobService {
    private final IntegrationJobRepository jobs;
    private final ProjectRepository projects;
    private final DocumentAccessService access;
    private final ObjectMapper mapper;
    private final List<IntegrationJobHandler> handlers;
    @Transactional
    public JobDto submit(Long projectId,Long actorId,IntegrationJob.Kind kind,String key,JsonNode request,String bindingKey) {
        access.requireRead(projectId,actorId);
        if(key==null || key.isBlank() || key.length()>128 || request==null || !request.isObject()
                || bindingKey==null || bindingKey.length()>255) throw new IllegalArgumentException("INVALID_JOB_REQUEST");
        projects.findForUpdate(projectId).orElseThrow(()->new SecurityException("ACCESS_DENIED"));
        String canonical=canonical(request).toString(); String hash=requestHash(kind,bindingKey,request);
        var existing=jobs.findByProjectIdAndActorIdAndKindAndIdempotencyKey(projectId,actorId,kind,key);
        if(existing.isPresent()) {
            if(!existing.get().getRequestHash().equals(hash)) throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
            return dto(existing.get());
        }
        if(jobs.countByProjectIdAndActorIdAndStateIn(projectId,actorId,List.of(IntegrationJob.State.QUEUED,IntegrationJob.State.RUNNING))>0)
            throw new IllegalStateException("CONCURRENCY_LIMIT");
        var now=Instant.now();
        if(jobs.countByProjectIdAndActorIdAndCreatedAtAfter(projectId,actorId,now.minusSeconds(60))>=3)
            throw new IllegalStateException("RATE_LIMITED");
        return dto(jobs.save(new IntegrationJob(projectId,actorId,kind,key,hash,canonical,bindingKey,now)));
    }
    @Transactional(readOnly=true)
    public Optional<JobDto> lookup(Long project,Long actor,IntegrationJob.Kind kind,String key,JsonNode request,String binding) {
        access.requireRead(project,actor);
        return jobs.findByProjectIdAndActorIdAndKindAndIdempotencyKey(project,actor,kind,key).map(job->{
            if(!job.getRequestHash().equals(requestHash(kind,binding,request))) throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
            return dto(job);
        });
    }
    private String requestHash(IntegrationJob.Kind kind,String binding,JsonNode request) {
        var identity=request.deepCopy();
        // Generated IDs are an implementation detail, not part of the client's request identity.
        if(kind==IntegrationJob.Kind.SPEC_CHANGE && identity.isObject())
            ((com.fasterxml.jackson.databind.node.ObjectNode)identity).remove("proposalId");
        return DocumentHash.of(kind+"\n"+binding+"\n"+canonical(identity));
    }
    @Transactional(readOnly=true)
    public JobDto get(Long projectId,Long actorId,UUID jobId) {
        access.requireRead(projectId,actorId);
        var job=jobs.findById(jobId).orElseThrow(()->new IllegalArgumentException("JOB_NOT_FOUND"));
        if(!Objects.equals(job.getProjectId(),projectId) || !Objects.equals(job.getActorId(),actorId))
            throw new SecurityException("ACCESS_DENIED");
        return dto(job);
    }
    @Transactional
    public Optional<IntegrationJob> claim() {
        var now=Instant.now();
        jobs.expired(now).forEach(j->{
            j.recover(now);
            if(j.getState()==IntegrationJob.State.FAILED)
                handlers.stream().filter(h->h.supports(j.getKind())).forEach(h->h.onFailure(j));
        });
        return jobs.claimable().map(job->{job.claim(UUID.randomUUID(),now);return job;});
    }
    @Transactional
    public void complete(UUID id,UUID lease,JsonNode result) { jobs.lock(id).orElseThrow().complete(lease,result.toString(),Instant.now()); }
    @Transactional
    public void providerStarted(UUID id,UUID lease) {jobs.lock(id).orElseThrow().providerStarted(lease,Instant.now());}
    @Transactional
    public void fail(UUID id,UUID lease,String error) { jobs.lock(id).orElseThrow().fail(lease,error,Instant.now()); }
    private JsonNode canonical(JsonNode value) {
        if(value.isObject()) {
            var sorted=mapper.createObjectNode(); var keys=new ArrayList<String>(); value.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys); keys.forEach(k->sorted.set(k,canonical(value.get(k)))); return sorted;
        }
        if(value.isArray()) {var array=mapper.createArrayNode();value.forEach(v->array.add(canonical(v)));return array;}
        return value;
    }
    private JobDto dto(IntegrationJob job) {
        try { return new JobDto(job.getJobId(),job.getKind(),job.getState(),job.getBindingKey(),job.getAttempts(),
            job.getResultJson()==null?null:mapper.readTree(job.getResultJson()),job.getErrorCode()); }
        catch(java.io.IOException e) {throw new IllegalStateException("Invalid job result",e);}
    }
}
