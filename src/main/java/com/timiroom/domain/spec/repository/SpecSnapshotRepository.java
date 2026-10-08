package com.timiroom.domain.spec.repository;
import com.timiroom.domain.spec.entity.SpecSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SpecSnapshotRepository extends JpaRepository<SpecSnapshot,UUID> {
    Optional<SpecSnapshot> findBySnapshotIdAndProjectId(UUID snapshotId,Long projectId);
    Optional<SpecSnapshot> findFirstByProjectIdOrderByRevisionDesc(Long projectId);
}
