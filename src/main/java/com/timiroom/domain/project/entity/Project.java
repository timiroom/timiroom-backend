package com.timiroom.domain.project.entity;

import com.timiroom.domain.project.enums.ProjectStatus;
import com.timiroom.global.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "project")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Project extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "project_name", length = 200, nullable = false)
    private String projectName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ProjectStatus status = ProjectStatus.PLANNING;

    public void updateStatus(ProjectStatus status) {
        this.status = status;
    }

    public void updateInfo(String projectName, String description) {
        if (projectName != null && !projectName.isBlank()) {
            this.projectName = projectName.trim();
        }
        if (description != null) {
            this.description = description.trim();
        }
    }
}
