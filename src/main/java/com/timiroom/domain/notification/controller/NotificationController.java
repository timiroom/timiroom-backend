package com.timiroom.domain.notification.controller;

import com.timiroom.domain.notification.service.NotificationService;
import com.timiroom.domain.notification.entity.Notification;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
    public ResponseEntity<?> markAsRead(HttpSession session, @PathVariable Long notificationId) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }
        try {
            notificationService.markAsRead(memberId, notificationId);
        } catch (NotificationService.NotificationNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "알림을 찾을 수 없습니다"));
        }
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
