package com.timiroom.domain.pipeline;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PipelineExecutionRepository extends JpaRepository<PipelineExecution, Long> {
    Optional<PipelineExecution> findByPipelineId(String pipelineId);
    List<PipelineExecution> findByMemberIdOrderByCreatedAtDesc(Long memberId);
}
