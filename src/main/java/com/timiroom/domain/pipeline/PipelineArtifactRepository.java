package com.timiroom.domain.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PipelineArtifactRepository extends JpaRepository<PipelineArtifact, Long> {
    List<PipelineArtifact> findByExecutionIdOrderByArtifactType(Long executionId);
    void deleteByExecutionIdIn(List<Long> executionIds);

    @Query("SELECT a FROM PipelineArtifact a WHERE a.executionId IN :executionIds AND a.artifactType = :type ORDER BY a.createdAt DESC")
    List<PipelineArtifact> findByExecutionIdsAndType(
            @Param("executionIds") List<Long> executionIds,
            @Param("type") PipelineArtifact.ArtifactType type);
}
