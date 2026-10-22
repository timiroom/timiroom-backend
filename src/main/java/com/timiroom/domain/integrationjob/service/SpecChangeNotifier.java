package com.timiroom.domain.integrationjob.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.timiroom.domain.integrationjob.entity.IntegrationJob;
import com.timiroom.domain.notification.enums.NotificationReferenceType;
import com.timiroom.domain.notification.enums.NotificationType;
import com.timiroom.domain.notification.service.NotificationService;
import com.timiroom.domain.project.entity.mapping.ProjectMember;
import com.timiroom.domain.project.enums.ProjectRole;
import com.timiroom.domain.project.repository.ProjectMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 변경안·교차검증 작업이 끝나면 화면 알림(종 아이콘)을 남긴다.
 * 승인할 사람(PM)이 Slack을 쓰지 않아도 검토 대기를 바로 알 수 있게 하려는 것이다.
 * 알림 실패가 작업 결과를 바꾸지 않도록 호출하는 쪽에서 예외를 삼킨다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpecChangeNotifier {

    private final NotificationService notifications;
    private final ProjectMemberRepository members;

    public void completed(IntegrationJob job, JsonNode result) {
        switch (job.getKind()) {
            case SPEC_CHANGE -> notify(pms(job), job, NotificationType.SPEC_CHANGE_READY,
                    "승인을 기다리는 변경안이 있습니다",
                    "AI가 만든 문서 변경안을 검토하고 교차검증 후 승인해 주세요.");
            case ARTIFACT_REVIEW -> {
                boolean passed = result != null && result.path("passed").asBoolean(false);
                Set<Long> to = pms(job);
                to.add(job.getActorId());
                notify(to, job, NotificationType.SPEC_REVIEW_DONE,
                        passed ? "문서 교차검증을 통과했습니다" : "문서 교차검증에서 확인할 항목이 나왔습니다",
                        passed ? "변경안을 승인하면 기준 명세에 반영됩니다."
                                : "결과를 확인하고 보완하거나, 문제없으면 '확인하고 승인'으로 반영하세요.");
            }
            default -> { }
        }
    }

    private Set<Long> pms(IntegrationJob job) {
        Set<Long> to = new LinkedHashSet<>();
        for (ProjectMember member : members.findByProjectId(job.getProjectId())) {
            if (member.getProjectRole() == ProjectRole.PM) to.add(member.getMemberId());
        }
        return to;
    }

    private void notify(Set<Long> to, IntegrationJob job, NotificationType type, String title, String content) {
        for (Long member : to) {
            notifications.create(member, job.getProjectId(), type, title, content,
                    NotificationReferenceType.PROJECT, job.getProjectId());
        }
    }
}
