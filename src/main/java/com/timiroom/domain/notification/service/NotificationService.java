package com.timiroom.domain.notification.service;

import com.timiroom.domain.notification.entity.Notification;
import com.timiroom.domain.notification.enums.NotificationReferenceType;
import com.timiroom.domain.notification.enums.NotificationType;
import com.timiroom.domain.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    @Transactional
    public Notification create(Long memberId, Long projectId, NotificationType type,
                               String title, String content,
                               NotificationReferenceType referenceType, Long referenceId) {
        return notificationRepository.save(Notification.builder()
                .memberId(memberId)
                .projectId(projectId)
                .type(type)
                .title(title)
                .content(content)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .build());
    }

    @Transactional(readOnly = true)
    public List<Notification> getMyNotifications(Long memberId) {
        return notificationRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }

    @Transactional(readOnly = true)
    public long countUnread(Long memberId) {
        return notificationRepository.countByMemberIdAndIsReadFalse(memberId);
    }

    /**
     * 본인 알림만 읽음 처리한다. 존재하지 않거나 다른 회원의 알림이면 존재 여부를 숨기기 위해 동일하게 NotFound.
     */
    @Transactional
    public void markAsRead(Long memberId, Long notificationId) {
        Notification notification = notificationRepository.findByNotificationIdAndMemberId(notificationId, memberId)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));
        if (!notification.isRead()) {
            notification.markAsRead();
            notificationRepository.save(notification);
        }
    }

    public static class NotificationNotFoundException extends RuntimeException {
        public NotificationNotFoundException(Long notificationId) {
            super("알림을 찾을 수 없습니다: " + notificationId);
        }
    }

    @Transactional
    public void markAllAsRead(Long memberId) {
        notificationRepository.findByMemberIdOrderByCreatedAtDesc(memberId)
                .forEach(n -> {
                    if (!n.isRead()) {
                        n.markAsRead();
                        notificationRepository.save(n);
                    }
                });
    }
}
