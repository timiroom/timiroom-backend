package com.timiroom.domain.project.repository;

import com.timiroom.domain.project.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT p FROM Project p WHERE p.projectId = :id")
    java.util.Optional<Project> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
    List<Project> findByTeamId(Long teamId);
}
