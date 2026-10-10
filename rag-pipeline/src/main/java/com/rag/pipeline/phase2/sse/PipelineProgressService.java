package com.rag.pipeline.phase2.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 파이프라인 실행 중 SSE 이벤트를 관리하는 서비스.
 *
 * subscribe() 호출 전에 send()가 먼저 불리는 경우를 대비해
 * 이벤트를 버퍼링하고, 구독 시 재전송한다.
 */
@Slf4j
@Service
public class PipelineProgressService {

    private final Map<String, SseEmitter>               emitters = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> buffers  = new ConcurrentHashMap<>();

    // ── 구독 ─────────────────────────────────────────────────────────

    public SseEmitter subscribe(String pipelineId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30분
        emitters.put(pipelineId, emitter);

        emitter.onCompletion(() -> emitters.remove(pipelineId));
        emitter.onTimeout(() -> {
            emitters.remove(pipelineId);
            buffers.remove(pipelineId);
        });
        emitter.onError(e -> emitters.remove(pipelineId));

        // 늦게 구독한 경우 버퍼된 이벤트 재전송
        List<Map<String, Object>> buffered = buffers.getOrDefault(pipelineId, List.of());
        for (Map<String, Object> evt : buffered) {
            try {
                emitter.send(SseEmitter.event()
                    .name((String) evt.get("type"))
                    .data(evt.get("data")));
            } catch (IOException e) {
                break;
            }
        }

        return emitter;
    }

    // ── 이벤트 발행 ──────────────────────────────────────────────────

    public void send(String pipelineId, String step, String message, int percent) {
        if (pipelineId == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("step",      step);
        data.put("message",   message);
        data.put("percent",   percent);
        data.put("timestamp", Instant.now().toString());
        buffer(pipelineId, "progress", data);
        emit(pipelineId,   "progress", data);
    }

    public void complete(String pipelineId, Object result) {
        if (pipelineId == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("result", result);
        emit(pipelineId, "complete", data);
        SseEmitter emitter = emitters.remove(pipelineId);
        if (emitter != null) {
            try { emitter.complete(); } catch (Exception ignored) {}
        }
        buffers.remove(pipelineId);
    }

    public void error(String pipelineId, String message) {
        if (pipelineId == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("message", message);
        buffer(pipelineId, "error", data);
        emit(pipelineId,   "error", data);
        SseEmitter emitter = emitters.remove(pipelineId);
        if (emitter != null) {
            try { emitter.complete(); } catch (Exception ignored) {}
        }
        buffers.remove(pipelineId);
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────

    private void buffer(String pipelineId, String type, Map<String, Object> data) {
        Map<String, Object> evt = new HashMap<>();
        evt.put("type", type);
        evt.put("data", data);
        buffers.computeIfAbsent(pipelineId, k -> new CopyOnWriteArrayList<>()).add(evt);
    }

    private void emit(String pipelineId, String type, Object data) {
        SseEmitter emitter = emitters.get(pipelineId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name(type).data(data));
        } catch (Exception e) {
            log.debug("SSE 전송 실패 — pipelineId: {}", pipelineId);
            emitters.remove(pipelineId);
        }
    }
}
