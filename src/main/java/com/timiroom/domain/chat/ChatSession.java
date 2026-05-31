package com.timiroom.domain.chat;

import com.timiroom.global.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "chat_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String sessionId;

    @Column(nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionStatus status;

    @Builder
    public ChatSession(String sessionId, Long memberId, SessionStatus status) {
        this.sessionId = sessionId;
        this.memberId = memberId;
        this.status = status;
    }

    public void complete() {
        this.status = SessionStatus.COMPLETED;
    }
}
