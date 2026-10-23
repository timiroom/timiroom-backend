package com.timiroom.domain.project.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 프로젝트를 지울 때 프로젝트에 딸린 기록을 함께 지운다.
 * 이 표들은 project에 외래키가 없어 그냥 두면 지운 프로젝트의 문서·변경안·연결이 남는다.
 * 사용량·결제 기록(ai_usage_event 등)은 정산 근거라 남긴다.
 * 테스트 DB처럼 일부 표가 없는 환경이 있어, 있는 표만 지운다(없는 표에 쿼리하면 Postgres 트랜잭션이 깨진다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectDataCleaner {

    /** 지우는 순서가 중요하다: 변경안·작업 → 스냅샷(변경안이 스냅샷을 참조). */
    private static final List<String> PROJECT_TABLES = List.of(
            "integration_job",
            "spec_change_proposal",
            "spec_snapshot",
            "qa_scenario",
            "commits",
            "project_repo_link",
            "github_pull_request_review_record",
            "mock_api_log",
            "notification",
            "slack_notification",
            "slack_project_channel");

    private final JdbcTemplate jdbc;
    private final Map<String, Boolean> existing = new ConcurrentHashMap<>();

    /** 문서 본문보다 먼저 불러야 한다: 문서 이력은 문서 id로만 프로젝트와 이어진다. */
    public void deleteArtifactRevisions(Long projectId) {
        if (!exists("artifact_revision")) return;
        jdbc.update("delete from artifact_revision where artifact_id in ("
                + "select a.artifact_id from pipeline_artifact a "
                + "join pipeline_execution e on e.execution_id = a.execution_id "
                + "join requirement r on r.requirement_id = e.requirement_id "
                + "where r.project_id = ?)", projectId);
    }

    public void deleteProjectRecords(Long projectId) {
        for (String table : PROJECT_TABLES) {
            if (!exists(table)) continue;
            int removed = jdbc.update("delete from " + table + " where project_id = ?", projectId);
            if (removed > 0) log.debug("프로젝트 {} 삭제: {} {}건", projectId, table, removed);
        }
    }

    private boolean exists(String table) {
        return existing.computeIfAbsent(table, name -> Boolean.TRUE.equals(jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
                    var meta = connection.getMetaData();
                    for (String candidate : List.of(name, name.toUpperCase())) {
                        try (ResultSet tables = meta.getTables(connection.getCatalog(), null, candidate, new String[]{"TABLE"})) {
                            if (tables.next()) return true;
                        }
                    }
                    return false;
                })));
    }
}
