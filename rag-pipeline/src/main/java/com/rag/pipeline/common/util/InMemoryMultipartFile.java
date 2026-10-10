package com.rag.pipeline.common.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * 요청 스레드에서 파일 바이트를 미리 읽어 비동기 컨텍스트에서도 안전하게 사용하기 위한 래퍼.
 * PDFParsingService.extractText()가 getBytes()만 호출하므로 최소 구현.
 */
public record InMemoryMultipartFile(String originalFilename, byte[] content) implements MultipartFile {

    @Override public String getName()                                             { return originalFilename; }
    @Override public String getOriginalFilename()                                 { return originalFilename; }
    @Override public String getContentType()                                      { return "application/pdf"; }
    @Override public boolean isEmpty()                                            { return content == null || content.length == 0; }
    @Override public long getSize()                                               { return content == null ? 0 : content.length; }
    @Override public byte[] getBytes()                                            { return content != null ? content : new byte[0]; }
    @Override public InputStream getInputStream()                                 { return new ByteArrayInputStream(getBytes()); }
    @Override public void transferTo(File dest) throws IOException                { throw new UnsupportedOperationException(); }
}
