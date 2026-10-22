package com.timiroom.domain.notification.enums;

public enum NotificationType {
    PIPELINE_COMPLETE, PIPELINE_FAILED, TEAM_INVITE, MENTION, PR_CONSISTENCY_REVIEW,
    /** 변경안이 만들어져 PM의 검토·승인을 기다린다 */
    SPEC_CHANGE_READY,
    /** 변경안 문서 교차검증이 끝났다 */
    SPEC_REVIEW_DONE
}
