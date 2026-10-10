package com.timiroom.domain.mock.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Mock API 호출 로그
 *
 * Mock 서버로 들어온 요청 1건의 기록.
 * 프론트엔드 개발자가 어떤 엔드포인트를 얼마나 호출했는지 추적하는 용도.
 */
@Entity
@Table(name = "mock_api_log", indexes = {
        @Index(name = "idx_mock_log_project", columnList = "project_id, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class MockApiLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(nullable = false, length = 500)
    private String endpoint;

    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    /** 매칭된 명세의 path 템플릿 (예: /api/v1/users/{id}). 매칭 실패 시 null */
    @Column(name = "matched_path", length = 500)
    private String matchedPath;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
