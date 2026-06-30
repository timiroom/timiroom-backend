package com.timiroom.domain.chat;

import com.timiroom.infra.ragpipeline.RagPipelineClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final RagPipelineClient ragPipelineClient;

    @Transactional
    public String createSession(Long memberId) {
        String sessionId = UUID.randomUUID().toString();
        sessionRepo.save(ChatSession.builder()
                .sessionId(sessionId)
                .memberId(memberId)
                .status(SessionStatus.ACTIVE)
                .build());
        log.info("채팅 세션 생성 | sessionId: {}, memberId: {}", sessionId, memberId);
        return sessionId;
    }

    @Transactional
    public Map<String, Object> sendMessage(String sessionId, Long memberId, String userContent) {
        ChatSession session = sessionRepo.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다: " + sessionId));

        if (!session.getMemberId().equals(memberId)) {
            throw new SecurityException("세션 접근 권한이 없습니다");
        }
        if (session.getStatus() == SessionStatus.COMPLETED) {
            throw new IllegalStateException("이미 완료된 세션입니다");
        }

        // 기존 메시지 목록 조회
        List<ChatMessage> existing = messageRepo.findBySessionIdOrderByOrderIndex(sessionId);
        int nextIndex = existing.size();

        // 사용자 메시지 저장
        messageRepo.save(ChatMessage.builder()
                .sessionId(sessionId)
                .role("user")
                .content(userContent)
                .orderIndex(nextIndex)
                .build());

        // rag-pipeline에 전체 대화 히스토리 전송
        List<Map<String, String>> history = existing.stream()
                .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                .collect(java.util.stream.Collectors.toList());
        history.add(Map.of("role", "user", "content", userContent));

        Map<String, Object> ragResponse = ragPipelineClient.chat(Map.of("messages", history));

        String assistantMessage = (String) ragResponse.get("message");
        boolean isComplete = Boolean.TRUE.equals(ragResponse.get("isComplete"));

        // 어시스턴트 응답 저장
        messageRepo.save(ChatMessage.builder()
                .sessionId(sessionId)
                .role("assistant")
                .content(assistantMessage)
                .orderIndex(nextIndex + 1)
                .build());

        // 완료 시 세션 상태 업데이트
        if (isComplete) {
            session.complete();
            log.info("채팅 세션 완료 | sessionId: {}", sessionId);
        }

        return ragResponse;
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> getMessages(String sessionId, Long memberId) {
        ChatSession session = sessionRepo.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다: " + sessionId));

        if (!session.getMemberId().equals(memberId)) {
            throw new SecurityException("세션 접근 권한이 없습니다");
        }

        return messageRepo.findBySessionIdOrderByOrderIndex(sessionId);
    }

    /**
     * 마지막 user 메시지를 rag-pipeline에 재전송
     * - 마지막 메시지가 user인 경우(assistant 응답 실패 상태)에만 동작
     */
    @Transactional
    public Map<String, Object> retryLastMessage(String sessionId, Long memberId) {
        ChatSession session = sessionRepo.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("세션을 찾을 수 없습니다: " + sessionId));

        if (!session.getMemberId().equals(memberId)) {
            throw new SecurityException("세션 접근 권한이 없습니다");
        }
        if (session.getStatus() == SessionStatus.COMPLETED) {
            throw new IllegalStateException("이미 완료된 세션은 재시도할 수 없습니다");
        }

        // 마지막 메시지 확인 — user여야 재시도 가능
        ChatMessage lastMessage = messageRepo.findTopBySessionIdOrderByOrderIndexDesc(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("재시도할 메시지가 없습니다"));

        if (!"user".equals(lastMessage.getRole())) {
            throw new IllegalStateException("마지막 메시지에 이미 응답이 있습니다");
        }

        log.info("메시지 재시도 | sessionId: {}, orderIndex: {}", sessionId, lastMessage.getOrderIndex());

        // 전체 히스토리 재구성 후 rag-pipeline 호출
        List<ChatMessage> history = messageRepo.findBySessionIdOrderByOrderIndex(sessionId);
        List<Map<String, String>> messages = history.stream()
                .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                .collect(java.util.stream.Collectors.toList());

        Map<String, Object> ragResponse = ragPipelineClient.chat(Map.of("messages", messages));

        String assistantMessage = (String) ragResponse.get("message");
        boolean isComplete = Boolean.TRUE.equals(ragResponse.get("isComplete"));

        messageRepo.save(ChatMessage.builder()
                .sessionId(sessionId)
                .role("assistant")
                .content(assistantMessage)
                .orderIndex(lastMessage.getOrderIndex() + 1)
                .build());

        if (isComplete) {
            session.complete();
            log.info("재시도 후 채팅 세션 완료 | sessionId: {}", sessionId);
        }

        return ragResponse;
    }
}
