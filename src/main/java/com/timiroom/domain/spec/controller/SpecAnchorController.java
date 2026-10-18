package com.timiroom.domain.spec.controller;

import com.timiroom.domain.integration.service.IntegrationActorResolver;
import com.timiroom.domain.spec.dto.AnchorVerification;
import com.timiroom.domain.spec.dto.SpecAnchorDto;
import com.timiroom.domain.spec.service.SpecAnchorVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 명세 발행 지문(블록체인 기록) 상태·검증·매니페스트. 오류 형식은 IntegrationExceptionHandler가 맞춘다(같은 패키지). */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{project}/spec-snapshots")
@ConditionalOnProperty(name="integration.enabled",havingValue="true")
public class SpecAnchorController {
    private final SpecAnchorVerifier anchors;
    private final IntegrationActorResolver actors;
    @GetMapping("/{id}/anchor") public SpecAnchorDto anchor(Authentication auth,@PathVariable Long project,@PathVariable UUID id) {
        return anchors.anchor(project,actors.memberId(auth),id);
    }
    @PostMapping("/{id}/anchor/verify") public AnchorVerification verify(Authentication auth,@PathVariable Long project,@PathVariable UUID id) {
        return anchors.verify(project,actors.memberId(auth),id);
    }
    /** 저장된 매니페스트 원문을 바이트 그대로 내려준다. 받은 파일의 SHA-256이 체인 기록과 같으면 외부에서도 검증된다. */
    @GetMapping("/{id}/anchor/manifest") public ResponseEntity<byte[]> manifest(Authentication auth,@PathVariable Long project,@PathVariable UUID id) {
        var manifest=anchors.manifest(project,actors.memberId(auth),id);
        String file="align-it-project-"+project+"-r"+manifest.revision()+"-manifest.json";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file).build().toString())
            .body(manifest.json().getBytes(StandardCharsets.UTF_8));
    }
}
