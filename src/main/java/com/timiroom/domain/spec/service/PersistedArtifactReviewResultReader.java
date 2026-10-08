package com.timiroom.domain.spec.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.integrationjob.entity.IntegrationJob;
import com.timiroom.domain.integrationjob.repository.IntegrationJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class PersistedArtifactReviewResultReader implements ArtifactReviewResultReader {
    private final IntegrationJobRepository jobs;
    private final ObjectMapper mapper;
    @Override @Transactional(readOnly=true)
    public boolean hasPass(UUID id,int revision,String hash) {
        var result=latest(id,revision,hash);
        if(result==null || !result.path("inputComplete").isBoolean() || !result.path("inputComplete").asBoolean()
                || !result.path("passed").isBoolean() || !result.path("passed").asBoolean()
                || !result.path("findings").isArray()) return false;
        for(var finding:result.get("findings")) if(!java.util.Set.of("PASS","INFO").contains(finding.path("severity").asText())) return false;
        return true;
    }
    @Override @Transactional(readOnly=true)
    public com.fasterxml.jackson.databind.JsonNode latest(UUID id,int revision,String hash) {
        String binding=id+":"+revision+":"+hash;
        return jobs.findByBindingKeyAndKindAndState(binding,IntegrationJob.Kind.ARTIFACT_REVIEW,IntegrationJob.State.COMPLETED)
            .stream().max(java.util.Comparator.comparing(IntegrationJob::getCompletedAt)).map(job->{
                try {
                    var result=mapper.readTree(job.getResultJson());
                    if(!result.isObject() || !result.path("passed").isBoolean() || !result.path("findings").isArray()) return mapper.nullNode();
                    return result;
                } catch(java.io.IOException e) { return mapper.nullNode(); }
            }).orElse(mapper.nullNode());
    }
}
