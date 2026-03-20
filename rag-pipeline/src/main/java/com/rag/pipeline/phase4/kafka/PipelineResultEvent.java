package com.rag.pipeline.phase4.kafka;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Kafka 메시지 DTO
 *
 * Phase 3 검증 통과 후 Kafka 토픽에 발행되는 이벤트 객체.
 * Consumer가 이 객체를 받아서 저장 처리를 합니다.
 *
 * @NoArgsConstructor — Kafka JSON 역직렬화 시 필요
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineResultEvent {

    /** 파이프라인 실행 ID (UUID) */
    private String pipelineId;

    /** 사용자 원본 쿼리 */
    private String userQuery;

    /** PM 에이전트가 도출한 기능 목록 */
    private List<String> featureList;

    /** DBA 에이전트가 생성한 DB 스키마 JSON */
    private String dbSchema;

    /** API 에이전트가 생성한 API 스펙 JSON */
    private String apiSpec;

    /** 재시도 횟수 */
    private int retryCount;

    /** 이벤트 생성 시각 */
    private LocalDateTime createdAt;
}