package com.timiroom.domain.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/webhooks/github")
public class GithubWebhookController {

    private final GithubWebhookVerifier githubWebhookVerifier;
    private final GithubWebhookService githubWebhookService;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<?> receive(@RequestBody byte[] payload,
                                     @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
                                     @RequestHeader(value = "X-GitHub-Event", required = false) String event) {
        if (!githubWebhookVerifier.verify(payload, signature)) {
            return ResponseEntity.status(401).body(java.util.Map.of("error", "Invalid GitHub webhook signature"));
        }
        try {
            JsonNode body = objectMapper.readTree(payload);
            githubWebhookService.handle(event, body);
            return ResponseEntity.accepted().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Invalid GitHub webhook payload"));
        }
    }
}
