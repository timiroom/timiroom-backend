package com.rag.pipeline.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_INPUT("COMMON_001", "입력값이 올바르지 않습니다"),
    INTERNAL_ERROR("COMMON_002", "서버 내부 오류가 발생했습니다"),

    // RAG
    RAG_CONTEXT_FAILED("RAG_001", "RAG 컨텍스트 생성에 실패했습니다"),
    DOCUMENT_INGEST_FAILED("RAG_002", "문서 저장에 실패했습니다"),
    EMBEDDING_FAILED("RAG_003", "임베딩 생성에 실패했습니다"),

    // 파이프라인
    PIPELINE_TIMEOUT("PIPELINE_001", "GPT 응답 시간이 초과되었습니다"),
    PIPELINE_VALIDATION_FAILED("PIPELINE_002", "결과물 검증에 실패했습니다"),
    PIPELINE_HUMAN_REVIEW("PIPELINE_003", "자동 검증 실패 — 관리자 검토가 필요합니다"),
    PIPELINE_QA_FAILED("PIPELINE_004", "QA 에이전트 검수에 실패했습니다"),

    // Kafka
    KAFKA_PUBLISH_FAILED("KAFKA_001", "Kafka 메시지 발행에 실패했습니다");

    private final String code;
    private final String message;
}