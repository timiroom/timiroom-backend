package com.timiroom.domain.pipeline.repository;

import com.timiroom.domain.pipeline.entity.ArtifactRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ArtifactRevisionRepository extends JpaRepository<ArtifactRevision, Long> {

    /** 이 아티팩트의 가장 최근 이전 버전 — 변경 비교의 기준이 된다 */
    Optional<ArtifactRevision> findFirstByArtifactIdOrderByVersionDesc(Long artifactId);
}
