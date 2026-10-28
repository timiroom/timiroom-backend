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
    private final org.springframework.beans.factory.ObjectProvider<SpecChangeNotifier> notifier;
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
            try { notifier.ifAvailable(n->n.completed(job,result)); }
            catch(RuntimeException notifyFailure) { log.warn("변경안 알림 실패: {}",job.getJobId()); }
        } catch(Exception failure) {
            var ai=aiFailure(failure);
            String code=ai!=null ? ai.code() : failure instanceof SecurityException ? "ACCESS_DENIED" :
                java.util.Set.of("PR_CHANGED","SPEC_CONFLICT","NO_SPEC_CHANGES","JOB_TIMEOUT","CONSISTENCY_PROVIDER_FAILED")
                    .contains(failure.getMessage()==null?"":failure.getMessage()) ? failure.getMessage() : "JOB_FAILED";
            try {
                jobs.fail(job.getJobId(),job.getLeaseId(),code);
                handlers.stream().filter(h->h.supports(job.getKind())).forEach(h->h.onFailure(job));
            }
            catch(IllegalStateException expired) { log.info("Job lease expired: {}",job.getJobId()); }
        }
    }
    /** AI 서버가 알려 준 실패(AI_로 시작하는 코드)면 그 이유를 작업 기록에 남긴다 */
    static com.timiroom.infra.ragpipeline.AiServiceException aiFailure(Throwable failure) {
        for(Throwable current=failure; current!=null; current=current.getCause())
            if(current instanceof com.timiroom.infra.ragpipeline.AiServiceException ai && ai.code().matches("AI_[A-Z_]{1,40}")) return ai;
        return null;
    }
}
