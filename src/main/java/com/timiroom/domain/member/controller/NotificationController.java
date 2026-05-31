package com.timiroom.domain.member.controller;

import com.timiroom.domain.member.entity.Notification;
import com.timiroom.domain.member.service.NotificationService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** 내 알림 목록 */
    @GetMapping
    public ResponseEntity<List<Notification>> myNotifications(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        return ResponseEntity.ok(notificationService.getMyNotifications(memberId));
    }

    /** 안읽은 알림 수 */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        return ResponseEntity.ok(Map.of("count", notificationService.countUnread(memberId)));
    }

    /** 알림 읽음 처리 */
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable Long notificationId) {
        notificationService.markAsRead(notificationId);
        return ResponseEntity.ok().build();
    }

    /** 전체 읽음 처리 */
    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        notificationService.markAllAsRead(memberId);
        return ResponseEntity.ok().build();
    }
}
