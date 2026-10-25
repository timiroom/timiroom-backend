package com.timiroom.domain.pipeline.repository;

import com.timiroom.domain.pipeline.entity.PipelineResultReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PipelineResultReceiptRepository extends JpaRepository<PipelineResultReceipt, String> {
    List<PipelineResultReceipt> findTop100ByCompletedFalseOrderByCreatedAtAsc();
}
