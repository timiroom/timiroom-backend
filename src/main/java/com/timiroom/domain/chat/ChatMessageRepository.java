package com.timiroom.domain.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findBySessionIdOrderByOrderIndex(String sessionId);
    int countBySessionId(String sessionId);
    Optional<ChatMessage> findTopBySessionIdOrderByOrderIndexDesc(String sessionId);
}
