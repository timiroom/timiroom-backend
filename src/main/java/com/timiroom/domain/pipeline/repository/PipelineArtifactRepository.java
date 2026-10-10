package com.timiroom.domain.pipeline.repository;

import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PipelineArtifactRepository extends JpaRepository<PipelineArtifact, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM PipelineArtifact a WHERE a.artifactId = :id")
    java.util.Optional<PipelineArtifact> findForUpdate(@Param("id") Long id);
    List<PipelineArtifact> findByExecutionIdOrderByArtifactType(Long executionId);
    void deleteByExecutionIdIn(List<Long> executionIds);

    @Query("SELECT a FROM PipelineArtifact a WHERE a.executionId IN :executionIds AND a.artifactType = :type ORDER BY a.createdAt DESC")
    List<PipelineArtifact> findByExecutionIdsAndType(
            @Param("executionIds") List<Long> executionIds,
            @Param("type") PipelineArtifact.ArtifactType type);
}
