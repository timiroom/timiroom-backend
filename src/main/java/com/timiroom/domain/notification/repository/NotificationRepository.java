package com.timiroom.domain.notification.repository;

import com.timiroom.domain.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByMemberIdOrderByCreatedAtDesc(Long memberId);
    long countByMemberIdAndIsReadFalse(Long memberId);
}
