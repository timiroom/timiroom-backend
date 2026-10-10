package com.timiroom.domain.integration.repository;
import com.timiroom.domain.integration.entity.IntegrationGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface IntegrationGrantRepository extends JpaRepository<IntegrationGrant,UUID> {
    Optional<IntegrationGrant> findByAuthorizationId(String id);
    List<IntegrationGrant> findByMemberIdOrderByCreatedAtDesc(Long memberId);
}
