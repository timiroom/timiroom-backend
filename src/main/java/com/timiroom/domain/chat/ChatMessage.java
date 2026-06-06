package com.timiroom.domain.chat;

import com.timiroom.global.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String sessionId;

    @Column(nullable = false, length = 20)
    private String role; // "user" | "assistant"

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private int orderIndex;

    @Builder
    public ChatMessage(String sessionId, String role, String content, int orderIndex) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.orderIndex = orderIndex;
    }
}
