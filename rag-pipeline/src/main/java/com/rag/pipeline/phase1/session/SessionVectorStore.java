package com.rag.pipeline.phase1.session;

import com.rag.pipeline.common.dto.DocumentChunk;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PDF 레퍼런스 문서를 파이프라인 세션 단위로 격리하여 저장한다.
 *
 * 현재: 인메모리 구현
 * 운영 환경: Redis TTL 30분으로 교체 권장
 *   → SessionVectorStore 인터페이스 추출 후
 *     InMemorySessionVectorStore / RedisSessionVectorStore 구현체 분리
 */
@Component
public class SessionVectorStore {

    private final Map<String, List<DocumentChunk>> store = new ConcurrentHashMap<>();

    /** PDF 청크를 세션에 누적 저장 (복수 PDF 지원) */
    public void store(String sessionId, List<DocumentChunk> chunks) {
        store.merge(sessionId, chunks, (existing, incoming) -> {
            List<DocumentChunk> merged = new ArrayList<>(existing);
            merged.addAll(incoming);
            return merged;
        });
    }

    public List<DocumentChunk> get(String sessionId) {
        return store.getOrDefault(sessionId, List.of());
    }

    public boolean hasSession(String sessionId) {
        return store.containsKey(sessionId);
    }

    /** 파이프라인 완료 후 명시적 정리 */
    public void clear(String sessionId) {
        store.remove(sessionId);
    }
}
