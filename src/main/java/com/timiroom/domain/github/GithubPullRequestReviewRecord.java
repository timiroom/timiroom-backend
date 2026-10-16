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

    /** 지식 그래프에서 PR 노드에 붙일 이름과 링크 — 그래프를 그릴 때마다 GitHub에 다시 묻지 않는다. */
    @Column(name = "pull_title", length = 400)
    private String pullTitle;

    @Column(name = "pull_url", length = 500)
    private String pullUrl;

    /**
     * open | closed. 닫히거나 머지된 PR은 지식 그래프에서 내린다.
     *
     * 그래프의 PR 노드는 "지금 이걸 건드리는 중"이라는 뜻이다. 머지가 끝난 변경은
     * 이미 명세와 코드 양쪽에 반영된 사실이지 진행 중인 위험이 아니므로, 남겨 두면
     * 몇 달 전 작업까지 영향 표시가 켜진 채로 쌓여 정작 지금 봐야 할 것을 덮는다.
     */
    @Column(name = "pull_state", length = 20)
    private String pullState;

    /**
     * 이 PR이 건드린 API 경로와 테이블 이름.
     *
     * 변경 파일에서 뽑아낸 결과를 검사 시점에 저장해 둔다. 지식 그래프는 요청마다
     * 새로 계산되는데, 그때마다 GitHub에서 변경 파일을 다시 받아오면 호출 한도에 걸리고
     * 화면도 느려진다. 파일을 이미 손에 쥐고 있는 검사 시점이 뽑아 두기 가장 좋은 자리다.
     *
     * 형태: {"apis":["/api/v1/reviews"],"tables":["reviews"],"files":["src/.../ReviewController.java"]}
     */
    @Column(name = "touched_json", columnDefinition = "TEXT")
    private String touchedJson;

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

    public void updateGraphContext(String pullTitle, String pullUrl, String pullState, String touchedJson) {
        this.pullTitle = pullTitle;
        this.pullUrl = pullUrl;
        this.pullState = pullState;
        this.touchedJson = touchedJson;
    }

    public void markClosed() {
        this.pullState = "closed";
    }

    /** 그래프에 올릴 대상인지 — 아직 열려 있고 무엇을 건드렸는지 아는 PR만 */
    public boolean isOpenForGraph() {
        return !"closed".equalsIgnoreCase(pullState) && touchedJson != null;
    }
}
