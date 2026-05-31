package com.timiroom.domain.pipeline.repositoty;

import com.timiroom.domain.pipeline.entity.PipelineExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PipelineExecutionRepository extends JpaRepository<PipelineExecution, Long> {
    Optional<PipelineExecution> findByPipelineId(String pipelineId);
    List<PipelineExecution> findByMemberIdOrderByCreatedAtDesc(Long memberId);
    List<PipelineExecution> findByRequirementIdOrderByCreatedAtDesc(Long requirementId);
    List<PipelineExecution> findByRequirementIdIn(List<Long> requirementIds);
    void deleteByRequirementIdIn(List<Long> requirementIds);
}
