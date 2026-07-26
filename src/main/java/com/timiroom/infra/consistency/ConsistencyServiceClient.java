package com.timiroom.infra.consistency;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

import java.time.Duration;

/** Friendli K-EXAONE을 사용하는 독립 Python 정합성 서비스 클라이언트. */
@Slf4j
@Component
public class ConsistencyServiceClient {

    private final WebClient webClient;

    public ConsistencyServiceClient(
            @Value("${consistency-service.base-url}") String baseUrl,
            @Value("${consistency-service.api-key:}") String apiKey) {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(config -> config.defaultCodecs().maxInMemorySize(50 * 1024 * 1024));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("X-Service-Key", apiKey);
        }
        this.webClient = builder.build();
        log.info("ConsistencyServiceClient initialized with base-url: {}", baseUrl);
    }

    public JsonNode reviewPullRequestConsistency(Object requestBody) {
        try {
            JsonNode response = webClient.post()
                    .uri("/api/v1/agents/pr-consistency/review")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(95))
                    .block();
            if (response == null || !response.path("findings").isArray()) {
                throw new IllegalStateException("Python Consistency Agent의 구조화된 응답이 없습니다");
            }
            return response;
        } catch (WebClientException e) {
            throw new IllegalStateException("Python 정합성 서비스에 연결할 수 없습니다", e);
        }
    }
}

