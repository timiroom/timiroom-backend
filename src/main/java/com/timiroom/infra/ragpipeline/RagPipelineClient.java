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
import reactor.core.publisher.Flux;

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
        Map<String, Object> response = webClient.post()
                .uri("/api/v1/orchestration/generate")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();

        if (response == null) throw new RuntimeException("rag-pipeline 응답이 없습니다");

        // ApiResponse { success, code, data: { pipelineId: "uuid" } }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        return (String) data.get("pipelineId");
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
                .doOnError(e -> log.error("SSE 스트림 오류 | pipelineId: {}", pipelineId, e));
    }
}
