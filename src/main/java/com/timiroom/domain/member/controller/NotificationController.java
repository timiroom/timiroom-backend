package com.timiroom.domain.member.controller;

import com.timiroom.domain.member.entity.Notification;
import com.timiroom.domain.member.exception.code.MemberSuccessCode;
import com.timiroom.domain.member.service.NotificationService;
import com.timiroom.global.ApiResponse;
import com.timiroom.global.apiPayload.code.BaseSuccessCode;
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
    public ApiResponse<List<Notification>> myNotifications(HttpSession session) {
        BaseSuccessCode code = MemberSuccessCode.OK;
        return ApiResponse.onSuccess(code, notificationService.getMyNotifications(session));
    }

    /** 안읽은 알림 수 */
    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount(HttpSession session) {
        BaseSuccessCode code = MemberSuccessCode.OK;
        return ApiResponse.onSuccess(code, notificationService.countUnread(session));
    }

    /** 알림 읽음 처리 */
    @PatchMapping("/{notificationId}/read")
    public ApiResponse<Void> markAsRead(@PathVariable Long notificationId) {
        notificationService.markAsRead(notificationId);
        BaseSuccessCode code = MemberSuccessCode.OK;
        return ApiResponse.onSuccess(code ,null);
    }

    /** 전체 읽음 처리 */
    @PatchMapping("/read-all")
    public ApiResponse<Void> markAllAsRead(HttpSession session) {
        notificationService.markAllAsRead(session);
        BaseSuccessCode code = MemberSuccessCode.OK;
        return ApiResponse.onSuccess(code, null);
    }
}
