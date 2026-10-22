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

    /** 진행 중인 실행만 끝난 상태로 바꾼다. 바꾼 줄 수가 1이면 이 호출이 결과 처리를 맡는다(중복 처리 방지). */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update PipelineExecution e set e.status = :status, e.retryCount = :retryCount, "
            + "e.errorMessage = :errorMessage, e.completedAt = CURRENT_TIMESTAMP "
            + "where e.pipelineId = :pipelineId and e.status = com.timiroom.domain.pipeline.entity.PipelineExecution.ExecutionStatus.RUNNING")
    int finishIfRunning(@org.springframework.data.repository.query.Param("pipelineId") String pipelineId,
                        @org.springframework.data.repository.query.Param("status") PipelineExecution.ExecutionStatus status,
                        @org.springframework.data.repository.query.Param("retryCount") int retryCount,
                        @org.springframework.data.repository.query.Param("errorMessage") String errorMessage);

    /** 진행 중인 실행(복구·이어 보기용) */
    List<PipelineExecution> findByStatus(PipelineExecution.ExecutionStatus status);

    List<PipelineExecution> findByMemberIdAndStatus(Long memberId, PipelineExecution.ExecutionStatus status);

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
