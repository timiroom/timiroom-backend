package com.timiroom.global.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * API 주소(api.timiroom.kro.kr)를 브라우저로 직접 열면 예전에는 {"error":"Unauthorized"}만 보였다.
 * AI 도구 연결 중에 이 주소를 보게 되는 사람이 있으므로 티미룸 화면으로 보낸다.
 */
@RestController
public class RootRedirectController {
    @Value("${frontend.url:http://localhost:3000}") private String frontend;

    @GetMapping("/")
    public ResponseEntity<Void> root() {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, URI.create(frontend.replaceAll("/+$", "") + "/").toString()).build();
    }
}
