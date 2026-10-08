package com.timiroom.domain.integrationjob.repository;
import com.timiroom.domain.integrationjob.entity.IntegrationJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.time.Instant;
import java.util.*;
public interface IntegrationJobRepository extends JpaRepository<IntegrationJob,UUID> {
    Optional<IntegrationJob> findByProjectIdAndActorIdAndKindAndIdempotencyKey(Long project,Long actor,IntegrationJob.Kind kind,String key);
    long countByProjectIdAndActorIdAndStateIn(Long project,Long actor,List<IntegrationJob.State> states);
    long countByProjectIdAndActorIdAndCreatedAtAfter(Long project,Long actor,Instant after);
    List<IntegrationJob> findByBindingKeyAndKindAndState(String binding,IntegrationJob.Kind kind,IntegrationJob.State state);
    List<IntegrationJob> findTop50ByProjectIdAndKindAndStateOrderByCreatedAtDesc(Long project,IntegrationJob.Kind kind,IntegrationJob.State state);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from IntegrationJob j where j.jobId=:id") Optional<IntegrationJob> lock(UUID id);
    @Query(value="select * from integration_job where state='QUEUED' order by created_at for update skip locked limit 1",nativeQuery=true)
    Optional<IntegrationJob> claimable();
    @Query(value="select * from integration_job where state='RUNNING' and lease_until <= :now for update skip locked",nativeQuery=true)
    List<IntegrationJob> expired(Instant now);
}
