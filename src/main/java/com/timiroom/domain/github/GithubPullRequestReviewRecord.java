package com.timiroom.domain.github;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** 같은 PR head SHA에 자동 review comment가 반복 게시되는 것을 막는 기록. */
@Entity
@Table(name = "github_pull_request_review_record",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "github_repo_pk", "pull_number"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class GithubPullRequestReviewRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "github_pr_review_record_id")
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "github_repo_pk", nullable = false)
    private Long githubRepoId;

    @Column(name = "pull_number", nullable = false)
    private Integer pullNumber;

    @Column(name = "head_sha", nullable = false, length = 80)
    private String headSha;

    @Column(name = "review_url", length = 500)
    private String reviewUrl;

    @Column(name = "check_run_url", length = 500)
    private String checkRunUrl;

    @Column(name = "score")
    private Integer score;

    /** 검사 시점의 ConsistencyFinding 목록을 JSON으로 직렬화해 저장 — 명세 패널 배지가 재검사 없이 읽는다. */
    @Column(name = "findings_json", columnDefinition = "TEXT")
    private String findingsJson;

    /** 실제 판정기 식별자. 중복 검사 응답에서도 원래 판정기를 그대로 노출한다. */
    @Column(name = "evaluator", length = 40)
    private String evaluator;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void updateReview(String headSha, String reviewUrl, String checkRunUrl) {
        this.headSha = headSha;
        this.reviewUrl = reviewUrl;
        this.checkRunUrl = checkRunUrl;
    }

    public void updateResult(Integer score, String findingsJson, String evaluator) {
        this.score = score;
        this.findingsJson = findingsJson;
        this.evaluator = evaluator;
    }
}
