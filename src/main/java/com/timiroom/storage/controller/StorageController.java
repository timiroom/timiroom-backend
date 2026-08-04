package com.timiroom.storage.controller;

import com.timiroom.storage.service.StorageService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;

    private static final long MAX_SIZE = 5 * 1024 * 1024L; // 5MB
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif"
    );

    @PostMapping("/upload")
    public ResponseEntity<?> upload(HttpSession session,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "folder", defaultValue = "misc") String folder) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) return ResponseEntity.status(401).build();

        if (file.isEmpty()) return ResponseEntity.badRequest().body("파일이 비어 있습니다");
        if (file.getSize() > MAX_SIZE) return ResponseEntity.badRequest().body("파일 크기는 5MB 이하여야 합니다");
        if (!ALLOWED_TYPES.contains(file.getContentType()))
            return ResponseEntity.badRequest().body("지원하지 않는 파일 형식입니다 (jpeg, png, webp, gif 허용)");

        try {
            String url = storageService.upload(file, folder);
            return ResponseEntity.ok(Map.of("url", url));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("업로드 실패: " + e.getMessage());
        }
    }
}
