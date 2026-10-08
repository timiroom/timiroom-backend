package com.timiroom.domain.integrationjob.service;
import com.timiroom.domain.integrationjob.entity.IntegrationJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Component @Slf4j @RequiredArgsConstructor
@ConditionalOnProperty(name="integration.enabled",havingValue="true")
public class IntegrationJobWorker {
    private final IntegrationJobService jobs;
    private final List<IntegrationJobHandler> handlers;
    @Scheduled(fixedDelayString="${integration.job.poll-ms:1000}",scheduler="integrationJobScheduler")
    public void poll() {
        jobs.claim().ifPresent(this::execute);
    }
    private void execute(IntegrationJob job) {
        try(var budget=JobTimeBudget.open(job.getLeaseUntil())) {
            var handler=handlers.stream().filter(h->h.supports(job.getKind())).findFirst().orElseThrow();
            if(handler.requiresProvider(job)) jobs.providerStarted(job.getJobId(),job.getLeaseId());
            var result=handler.execute(job);
            jobs.complete(job.getJobId(),job.getLeaseId(),result);
        } catch(Exception failure) {
            String code=failure instanceof SecurityException ? "ACCESS_DENIED" :
                java.util.Set.of("PR_CHANGED","SPEC_CONFLICT","NO_SPEC_CHANGES","JOB_TIMEOUT","CONSISTENCY_PROVIDER_FAILED")
                    .contains(failure.getMessage()==null?"":failure.getMessage()) ? failure.getMessage() : "JOB_FAILED";
            try {
                jobs.fail(job.getJobId(),job.getLeaseId(),code);
                handlers.stream().filter(h->h.supports(job.getKind())).forEach(h->h.onFailure(job));
            }
            catch(IllegalStateException expired) { log.info("Job lease expired: {}",job.getJobId()); }
        }
    }
}
