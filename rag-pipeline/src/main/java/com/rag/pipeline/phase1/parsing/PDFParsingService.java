package com.rag.pipeline.phase1.parsing;

import com.rag.pipeline.common.dto.DocumentChunk;
import com.rag.pipeline.phase1.chunking.SemanticChunkingService;
import com.rag.pipeline.phase1.session.SessionVectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PDFParsingService {

    private static final int MIN_TEXT_LENGTH = 100;
    private static final int MAX_FILES = 5;

    private final SemanticChunkingService chunkingService;
    private final SessionVectorStore sessionVectorStore;

    /**
     * 복수의 PDF 파일을 순차 처리한다.
     * 개별 파일 실패 시 나머지 파일은 계속 처리한다.
     */
    public void parseAndStoreAll(List<MultipartFile> files, String sessionId) {
        if (files == null || files.isEmpty()) {
            log.debug("[{}] 업로드된 PDF 없음, 스킵", sessionId);
            return;
        }
        if (files.size() > MAX_FILES) {
            log.warn("[{}] PDF 최대 {}개 제한 — {}개 중 앞 {}개만 처리",
                sessionId, MAX_FILES, files.size(), MAX_FILES);
        }
        files.stream().limit(MAX_FILES).forEach(f -> parseAndStore(f, sessionId));
    }

    private void parseAndStore(MultipartFile file, String sessionId) {
        String fileName = file.getOriginalFilename();
        log.info("[{}] PDF 파싱 시작: {}", sessionId, fileName);
        try {
            String text = extractText(file);

            // 스캔 PDF 감지
            if (text == null || text.trim().length() < MIN_TEXT_LENGTH) {
                log.warn("[{}] PDF 텍스트 {}자 미만 — 스캔 PDF이거나 빈 문서, 스킵: {}",
                    sessionId, MIN_TEXT_LENGTH, fileName);
                return;
            }

            // 세션 전용 저장 — pgvector가 아닌 인메모리에만 보관
            List<DocumentChunk> chunks = chunkingService.chunk(text, Map.of("source", fileName));
            sessionVectorStore.store(sessionId, chunks);

            log.info("[{}] PDF 세션 저장 완료: {} → {}개 청크",
                sessionId, fileName, chunks.size());

        } catch (IOException e) {
            // 파싱 실패는 치명적 오류 아님 — 경고 후 계속
            log.warn("[{}] PDF 파싱 실패, 스킵: {} — {}", sessionId, fileName, e.getMessage());
        }
    }

    private String extractText(MultipartFile file) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
