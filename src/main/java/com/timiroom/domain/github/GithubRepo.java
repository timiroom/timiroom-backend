package com.timiroom.domain.github;

import com.timiroom.global.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 시스템에 등록된 GitHub 레포지토리.
 * 하나의 설치(installation)를 통해 접근하며, 여러 프로젝트에 연결될 수 있다.
 * (프로젝트와의 연결은 {@link ProjectRepoLink}가 담당)
 */
@Entity
@Table(name = "github_repo")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class GithubRepo extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "github_repo_pk")
    private Long id;

    @Column(name = "github_repo_id", nullable = false, unique = true)
    private Long githubRepoId; // GitHub의 숫자 repo id (rename 대응)

    @Column(name = "full_name", length = 200, nullable = false)
    private String fullName; // "timiroom/timiroom-backend"

    @Column(name = "default_branch", length = 100)
    private String defaultBranch;

    @Column(name = "is_private", nullable = false)
    private boolean isPrivate;

    @Column(name = "installation_id", nullable = false)
    private Long installationId;

    public void updateMetadata(String fullName, String defaultBranch, boolean isPrivate, Long installationId) {
        this.fullName = fullName;
        this.defaultBranch = defaultBranch;
        this.isPrivate = isPrivate;
        this.installationId = installationId;
    }
}
