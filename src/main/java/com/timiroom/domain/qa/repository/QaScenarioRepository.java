package com.timiroom.domain.qa.repository;

import com.timiroom.domain.qa.entity.QaScenario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QaScenarioRepository extends JpaRepository<QaScenario, Long> {

    List<QaScenario> findByProjectIdOrderByCreatedAtAsc(Long projectId);
}
