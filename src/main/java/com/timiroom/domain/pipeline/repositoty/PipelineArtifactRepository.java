package com.timiroom.domain.pipeline.repositoty;

import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PipelineArtifactRepository extends JpaRepository<PipelineArtifact, Long> {
    List<PipelineArtifact> findByExecutionIdOrderByArtifactType(Long executionId);
    void deleteByExecutionIdIn(List<Long> executionIds);
}
