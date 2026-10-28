package com.timiroom.domain.github;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 프로젝트 ↔ GitHub 레포 연결 (프로젝트 1 : N 레포).
 * 한 프로젝트가 backend/frontend/infra 등 여러 레포에 걸치는 구조를 지원한다.
 */
@Entity
@Table(name = "project_repo_link",
       uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "github_repo_pk"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ProjectRepoLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "project_repo_link_id")
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** github_repo.github_repo_pk (내부 PK)를 참조 */
    @Column(name = "github_repo_pk", nullable = false)
    private Long githubRepoId;

    @Column(name = "role_hint", length = 20)
    private String roleHint; // BACKEND | FRONTEND | PIPELINE | INFRA 등 (표시용 힌트)

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public void updateRoleHint(String roleHint) {
        this.roleHint = roleHint;
    }
}
