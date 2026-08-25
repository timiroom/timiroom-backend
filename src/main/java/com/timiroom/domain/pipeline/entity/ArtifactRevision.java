package com.timiroom.domain.pipeline.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 아티팩트 이전 버전 보관
 *
 * 문서를 수정하면 덮어쓰기 전의 내용을 여기에 남긴다.
 * 무엇이 바뀌었는지 알아야 "이 변경이 어디에 영향을 주는지"를 계산할 수 있고,
 * 그것이 이 서비스가 파는 정합성의 핵심이다.
 *
 * pipeline_artifact 테이블에 버전 행을 쌓지 않고 따로 두는 이유는,
 * 기존 조회가 "종류별 최신 하나"를 전제로 짜여 있어 행이 늘면 그 전제가 깨지기 때문이다.
 */
@Entity
@Table(name = "artifact_revision", indexes = {
        @Index(name = "idx_revision_artifact", columnList = "artifact_id, version")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ArtifactRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "revision_id")
    private Long revisionId;

    @Column(name = "artifact_id", nullable = false)
    private Long artifactId;

    /** 이 내용이 몇 번째 버전이었는지 */
    @Column(nullable = false)
    private int version;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
