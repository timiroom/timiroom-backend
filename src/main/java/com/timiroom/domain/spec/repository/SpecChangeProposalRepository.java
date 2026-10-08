package com.timiroom.domain.spec.repository;
import com.timiroom.domain.spec.entity.SpecChangeProposal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface SpecChangeProposalRepository extends JpaRepository<SpecChangeProposal, UUID> {
    Optional<SpecChangeProposal> findByProposalIdAndProjectId(UUID proposalId, Long projectId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SpecChangeProposal p where p.proposalId = :proposalId and p.projectId = :projectId")
    Optional<SpecChangeProposal> findForUpdate(UUID proposalId, Long projectId);
}
