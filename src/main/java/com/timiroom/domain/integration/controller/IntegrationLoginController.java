package com.timiroom.domain.integration.controller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

/**
 * AI 도구 연결 중 티미룸 로그인이 필요할 때 오는 주소. 티미룸 설정 → 연결된 도구 화면으로 보낸다.
 * 로그인이 안 돼 있으면 티미룸의 원래 로그인 창을 쓰고, 그 로그인도 이 서버의 /oauth2/authorization/{google|github}를
 * 거치므로 로그인한 뒤에는 같은 세션에 저장해 둔 승인 요청(/oauth2/authorize)으로 이어진다.
 */
@RestController
@ConditionalOnProperty(name="integration.enabled",havingValue="true")
public class IntegrationLoginController {
    @Value("${frontend.url:http://localhost:3000}") private String frontend;

    @GetMapping("/integrations/login")
    public ResponseEntity<Void> login() {
        return ResponseEntity.status(HttpStatus.FOUND).header("Cache-Control","no-store")
            .location(URI.create(frontend.replaceAll("/+$","")+"/settings/connections")).build();
    }
}
