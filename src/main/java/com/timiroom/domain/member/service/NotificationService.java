package com.timiroom.domain.member.service;

import com.timiroom.domain.member.entity.Notification;
import com.timiroom.domain.member.enums.NotificationReferenceType;
import com.timiroom.domain.member.enums.NotificationType;
import com.timiroom.domain.member.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;

    @Transactional
    public Notification create(Long memberId, NotificationType type,
                               String title, String content,
                               NotificationReferenceType referenceType, Long referenceId) {
        return notificationRepository.save(Notification.builder()
                .memberId(memberId)
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

    @Transactional
    public void markAsRead(Long notificationId) {
        notificationRepository.findById(notificationId)
                .ifPresent(n -> {
                    n.markAsRead();
                    notificationRepository.save(n);
                });
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
