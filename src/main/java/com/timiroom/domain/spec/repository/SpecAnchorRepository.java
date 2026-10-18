package com.timiroom.domain.spec.repository;
import com.timiroom.domain.spec.entity.SpecAnchor;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SpecAnchorRepository extends JpaRepository<SpecAnchor,UUID> {
    Optional<SpecAnchor> findBySnapshotId(UUID snapshotId);
    Optional<SpecAnchor> findByProjectIdAndRevision(Long projectId,int revision);
    Optional<SpecAnchor> findFirstByProjectIdOrderByRevisionDesc(Long projectId);
}
