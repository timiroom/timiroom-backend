package com.timiroom.domain.qa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * QA 테스트 시나리오와 (있다면) 가장 최근 평가 결과.
 *
 * 실행 이력을 따로 쌓지 않고 시나리오당 최신 판정 하나만 들고 있다 — 명세가 바뀌면
 * 이전 판정은 의미를 잃으므로, 다시 실행하면 그 자리에서 덮어쓴다.
 */
@Entity
@Table(name = "qa_scenario", indexes = {
        @Index(name = "idx_qa_scenario_project", columnList = "project_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class QaScenario {

    public enum Type { NORMAL, EXCEPTION, BOUNDARY }

    public enum Source { AI, USER }

    public enum Verdict { PASS, FAIL, INCONCLUSIVE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "qa_scenario_id")
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "feature_name", length = 200)
    private String featureName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String given;

    @Column(name = "when_step", columnDefinition = "TEXT")
    private String whenStep;

    @Column(name = "then_result", columnDefinition = "TEXT")
    private String thenResult;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Source source;

    @Column(name = "created_by_member_id")
    private Long createdByMemberId;

    @Enumerated(EnumType.STRING)
    @Column(length = 15)
    private Verdict verdict;

    @Column(columnDefinition = "TEXT")
    private String reasoning;

    /** 판정 근거로 쓴 코드/명세 조각 목록을 JSON 배열 문자열로 저장 */
    @Column(name = "evidence_json", columnDefinition = "TEXT")
    private String evidenceJson;

    @Column(name = "evaluated_at")
    private LocalDateTime evaluatedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void updateContent(String featureName, Type type, String title, String given, String whenStep, String thenResult) {
        this.featureName = featureName;
        this.type = type;
        this.title = title;
        this.given = given;
        this.whenStep = whenStep;
        this.thenResult = thenResult;
        clearResult();
    }

    public void recordResult(Verdict verdict, String reasoning, String evidenceJson, LocalDateTime evaluatedAt) {
        this.verdict = verdict;
        this.reasoning = reasoning;
        this.evidenceJson = evidenceJson;
        this.evaluatedAt = evaluatedAt;
    }

    /** 시나리오 내용이 바뀌면 이전 판정은 더 이상 유효하지 않다 */
    private void clearResult() {
        this.verdict = null;
        this.reasoning = null;
        this.evidenceJson = null;
        this.evaluatedAt = null;
    }
}
