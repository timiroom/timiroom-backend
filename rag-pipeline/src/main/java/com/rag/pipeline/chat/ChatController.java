package com.rag.pipeline.chat;

import com.rag.pipeline.chat.dto.ChatRequest;
import com.rag.pipeline.chat.dto.ChatResponse;
import com.rag.pipeline.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final IntakeChatService intakeChatService;

    /**
     * 프로젝트 인테이크 채팅 — stateless.
     * 클라이언트가 전체 대화 히스토리를 매 요청마다 전송.
     */
    @PostMapping("/message")
    public ApiResponse<ChatResponse> message(@Valid @RequestBody ChatRequest request) {
        ChatResponse response = intakeChatService.chat(request.messages());
        return ApiResponse.ok(response);
    }
}
