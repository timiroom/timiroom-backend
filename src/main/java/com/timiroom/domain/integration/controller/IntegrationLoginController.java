package com.timiroom.domain.integration.controller;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="integration.enabled",havingValue="true")
public class IntegrationLoginController {
    @GetMapping(value="/integrations/login",produces="text/html;charset=UTF-8")
    public ResponseEntity<String> login() {
        return ResponseEntity.ok().header("Cache-Control","no-store").body("""
            <!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>티미룸 계정 연결</title><style>body{font-family:system-ui;background:#f7f6f3;color:#1a1916;max-width:480px;margin:12vh auto;padding:24px}a{display:block;padding:16px;margin:16px 0;background:white;border:1px solid #dedcd5;border-radius:10px;color:#633ac7}p{line-height:1.7}</style>
            <main><h1>티미룸 계정 연결</h1><p>프로젝트를 만들 때 사용한 계정으로 로그인해 주세요. 로그인 후 연결할 프로젝트와 권한을 선택합니다.</p>
            <a href="/oauth2/authorization/google">Google 계정으로 계속</a><a href="/oauth2/authorization/github">GitHub 계정으로 계속</a></main></html>
            """);
    }
}
