package com.timiroom.domain.aikey.repository;

import com.timiroom.domain.aikey.entity.LlmCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LlmCredentialRepository extends JpaRepository<LlmCredential, Long> {
    List<LlmCredential> findByMemberIdOrderByCreatedAtAsc(Long memberId);
    Optional<LlmCredential> findByIdAndMemberId(Long id, Long memberId);
    Optional<LlmCredential> findFirstByMemberIdAndActiveTrue(Long memberId);
    long countByMemberId(Long memberId);
}
