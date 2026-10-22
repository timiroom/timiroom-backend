package com.timiroom.domain.spec;
import com.timiroom.domain.integrationjob.entity.IntegrationJob;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class IntegrationJobStateTest {
    @Test void expiredProviderAttemptIsNeverAutomaticallyBilledAgain() {
        var job=job();var lease=UUID.randomUUID();job.claim(lease,Instant.EPOCH);
        job.providerStarted(lease,Instant.EPOCH.plusSeconds(1));
        job.recover(Instant.EPOCH.plusSeconds(301));
        assertThat(job.getState()).isEqualTo(IntegrationJob.State.FAILED);
        assertThat(job.getErrorCode()).isEqualTo("PROVIDER_OUTCOME_UNKNOWN");
        assertThatThrownBy(()->job.claim(UUID.randomUUID(),Instant.EPOCH.plusSeconds(302))).isInstanceOf(IllegalStateException.class);
    }
    IntegrationJob job() { return new IntegrationJob(1L,2L,IntegrationJob.Kind.ARTIFACT_REVIEW,"key","request-hash","{}","proposal-binding",Instant.EPOCH); }
    @Test void lostWorkerCannotCompleteNewLease() {
        var job=job(); var lease1=UUID.randomUUID(); var lease2=UUID.randomUUID();
        job.claim(lease1,Instant.EPOCH);
        job.recover(Instant.EPOCH.plusSeconds(301));
        job.claim(lease2,Instant.EPOCH.plusSeconds(302));
        assertThatThrownBy(()->job.complete(lease1,"{}",Instant.EPOCH.plusSeconds(303))).isInstanceOf(IllegalStateException.class);
        job.complete(lease2,"{\"passed\":true}",Instant.EPOCH.plusSeconds(304));
        assertThat(job.getState()).isEqualTo(IntegrationJob.State.COMPLETED);
    }
    @Test void recoveryStopsAfterTwoAttempts() {
        var job=job(); job.claim(UUID.randomUUID(),Instant.EPOCH);
        job.recover(Instant.EPOCH.plusSeconds(301));
        job.claim(UUID.randomUUID(),Instant.EPOCH.plusSeconds(302));
        job.recover(Instant.EPOCH.plusSeconds(603));
        assertThat(job.getState()).isEqualTo(IntegrationJob.State.FAILED);
        assertThat(job.getAttempts()).isEqualTo(2);
    }
    @Test void activeLeaseCannotBeRecoveredPrematurely() {
        var job=job(); job.claim(UUID.randomUUID(),Instant.EPOCH);
        job.recover(Instant.EPOCH.plusSeconds(20));
        assertThat(job.getState()).isEqualTo(IntegrationJob.State.RUNNING);
    }
}
