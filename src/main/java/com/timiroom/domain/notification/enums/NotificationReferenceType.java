package com.timiroom.domain.notification.enums;

/**
 * Notification.referenceId 가 가리키는 대상.
 * PIPELINE: pipeline_execution.execution_id
 * PULL_REQUEST: GitHub PR 번호(저장소 안의 number). 저장소 id가 아니므로 projectId와 함께 해석한다.
 * PROJECT: project_id
 * TEAM: team_id
 */
public enum NotificationReferenceType {
    PIPELINE, PROJECT, TEAM, PULL_REQUEST
}
