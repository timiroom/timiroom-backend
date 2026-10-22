package com.timiroom.domain.notification.entity;

import com.timiroom.domain.notification.enums.NotificationReferenceType;
import com.timiroom.domain.notification.enums.NotificationType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long notificationId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** 알림이 속한 프로젝트. 클릭 시 화면 이동에 사용한다. 과거 알림 등 알 수 없으면 null. */
    @Column(name = "project_id")
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(length = 300, nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", length = 20)
    private NotificationReferenceType referenceType;

    /**
     * referenceType별 의미:
     * PIPELINE -> pipeline_execution.execution_id,
     * PULL_REQUEST -> GitHub PR 번호(저장소 내 number, 전역 고유 id 아님),
     * PROJECT -> project_id, TEAM -> team_id.
     */
    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean isRead = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public void markAsRead() {
        this.isRead = true;
    }
}
