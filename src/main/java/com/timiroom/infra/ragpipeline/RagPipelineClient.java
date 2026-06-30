package com.timiroom.infra.ragpipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class RagPipelineClient {

    private final WebClient webClient;

    public RagPipelineClient(
            @Value("${rag-pipeline.base-url}") String baseUrl,
            ObjectMapper objectMapper) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(config -> config.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
        log.info("RagPipelineClient initialized with base-url: {}", baseUrl);
    }

    private RuntimeException unavailable(String op, Throwable cause) {
        log.error("RAG 파이프라인 연결 실패 [{}]: {}", op, cause.getMessage());
        return new IllegalStateException("RAG 파이프라인 서비스에 연결할 수 없습니다. 서버가 실행 중인지 확인해주세요.", cause);
    }

    /**
     * 파이프라인 시작 요청 → pipelineId(UUID) 반환
     * POST /api/v1/orchestration/generate (multipart/form-data)
     */
    public String generate(String requestJson, List<MultipartFile> files) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("request", requestJson).contentType(MediaType.APPLICATION_JSON);

        if (files != null) {
            for (MultipartFile file : files) {
                try {
                    byte[] bytes = file.getBytes();
                    builder.part("files", new ByteArrayResource(bytes) {
                        @Override
                        public String getFilename() { return file.getOriginalFilename(); }
                    }).contentType(MediaType.APPLICATION_OCTET_STREAM);
                } catch (Exception e) {
                    throw new RuntimeException("파일 처리 실패: " + file.getOriginalFilename(), e);
                }
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> response;
        try {
            response = webClient.post()
                    .uri("/api/v1/orchestration/generate")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (WebClientRequestException e) {
            throw unavailable("generate", e);
        }

        if (response == null) throw new RuntimeException("rag-pipeline 응답이 없습니다");

        // ApiResponse { success, code, data: { pipelineId: "uuid" } }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        return (String) data.get("pipelineId");
    }

    /**
     * 프로젝트 인테이크 채팅 메시지 전송
     * POST /api/v1/chat/message
     */
    public Map<String, Object> chat(Object requestBody) {
        @SuppressWarnings("unchecked")
        Map<String, Object> response;
        try {
            response = webClient.post()
                    .uri("/api/v1/chat/message")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (WebClientRequestException e) {
            throw unavailable("chat", e);
        }

        if (response == null) throw new RuntimeException("rag-pipeline 채팅 응답이 없습니다");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        return data;
    }

    /**
     * SSE 진행 스트림 구독
     * GET /api/v1/orchestration/progress/{pipelineId}
     * - event: "progress" | "complete" | "error"
     */
    public Flux<ServerSentEvent<String>> getProgressFlux(String pipelineId) {
        return webClient.get()
                .uri("/api/v1/orchestration/progress/{id}", pipelineId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .onErrorMap(WebClientRequestException.class, e -> unavailable("progress", e))
                .doOnError(e -> log.error("SSE 스트림 오류 | pipelineId: {}", pipelineId, e));
    }
}
