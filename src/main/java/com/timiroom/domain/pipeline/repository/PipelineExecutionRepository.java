package com.timiroom.domain.pipeline.repository;

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

    /**
     * 프로젝트의 "현재" 실행을 고른다: 최신 요구사항부터 차례로 보면서 가장 최근의 COMPLETED 실행.
     * 문서 조회(GET artifacts / documents)와 문서 저장이 모두 이 선택을 공유해야 저장한 문서가 조회에 보인다.
     *
     * @param requirementIdsNewestFirst 프로젝트 요구사항 id (createdAt 내림차순)
     */
    default Optional<PipelineExecution> findCurrentCompleted(List<Long> requirementIdsNewestFirst) {
        return requirementIdsNewestFirst.stream()
                .flatMap(requirementId -> findByRequirementIdOrderByCreatedAtDesc(requirementId).stream())
                .filter(execution -> execution.getStatus() == PipelineExecution.ExecutionStatus.COMPLETED)
                .findFirst();
    }
}
