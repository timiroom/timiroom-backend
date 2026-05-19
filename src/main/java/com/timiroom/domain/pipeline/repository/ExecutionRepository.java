package com.timiroom.domain.pipeline.repository;

import com.timiroom.domain.pipeline.entity.PipelineExecution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRepository extends JpaRepository<PipelineExecution,Long> {
}
