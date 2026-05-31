package com.timiroom.domain.chat;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /** 채팅 세션 생성 */
    @PostMapping("/sessions")
    public ResponseEntity<Map<String, String>> createSession(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        String sessionId = chatService.createSession(memberId);
        return ResponseEntity.ok(Map.of("sessionId", sessionId));
    }

    /** 메시지 전송 → Claude 응답 반환 */
    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<Map<String, Object>> sendMessage(
            HttpSession session,
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body
    ) {
        Long memberId = (Long) session.getAttribute("memberId");
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "메시지 내용이 없습니다"));
        }
        Map<String, Object> response = chatService.sendMessage(sessionId, memberId, content);
        return ResponseEntity.ok(response);
    }

    /** 세션 대화 히스토리 조회 */
    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<List<ChatMessage>> getMessages(
            HttpSession session,
            @PathVariable String sessionId
    ) {
        Long memberId = (Long) session.getAttribute("memberId");
        List<ChatMessage> messages = chatService.getMessages(sessionId, memberId);
        return ResponseEntity.ok(messages);
    }
}
