package com.timiroom.domain.mock.repository;

import com.timiroom.domain.mock.entity.MockApiLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MockApiLogRepository extends JpaRepository<MockApiLog, Long> {
    List<MockApiLog> findByProjectIdOrderByCreatedAtDesc(Long projectId, Pageable pageable);
}
