package com.rag.pipeline.chat.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ChatRequest(
    @NotEmpty(message = "메시지 목록이 비어있습니다")
    List<ChatMessageDto> messages
) {}
