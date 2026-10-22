package com.timiroom.infra.ragpipeline;

import com.timiroom.domain.usage.service.AiUsageScope;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import com.timiroom.domain.integrationjob.service.JobTimeBudget;
import java.util.List;
import java.util.Map;

/** Adapts the existing section editor and Spring RAG Agent; no model of its own. */
@Component
public class DocumentEditClient {
    private final WebClient editor;
    private final WebClient agent;
    private final String model;
    public DocumentEditClient(@Value("${rag-pipeline.base-url}") String editUrl,
            @Value("${document-impact.base-url:${rag-pipeline.base-url}}") String agentUrl,
            @Value("${document-impact.model:gpt-5.4-mini}") String model,
            @Value("${rag-pipeline.internal-token:}") String internalToken) {
        this.editor = webClient(editUrl, internalToken);
        this.agent = webClient(agentUrl, internalToken);
        this.model = model;
    }
    private static WebClient webClient(String url, String internalToken) {
        var builder = WebClient.builder().baseUrl(url).codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024));
        // AI 서버는 이 토큰이 있어야 AI 경로를 열어 준다.
        if (internalToken != null && !internalToken.isBlank()) builder.defaultHeader("X-Timiroom-Internal-Token", internalToken.trim());
        return builder.build();
    }
    private static JsonNode body(org.springframework.http.ResponseEntity<JsonNode> entity) {
        if (entity == null) return null;
        AiUsageScope.addHeader(entity.getHeaders().getFirst("X-Timiroom-Usage"));
        return entity.getBody();
    }
    public JsonNode edit(String docType, JsonNode document, String instruction) {
        JsonNode response;
        try {
            response = body(editor.post().uri("/api/v1/document/{type}/edit", docType)
                .header(AiUsageScope.TRACKING_HEADER, AiUsageScope.active() ? "1" : "0")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("document", document, "instruction", instruction, "history", List.of()))
                .retrieve().toEntity(JsonNode.class).timeout(JobTimeBudget.limit(Duration.ofSeconds(240))).block());
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            // 내 AI 키 오류·크레딧 소진 같은 이유를 작업 기록까지 남긴다
            throw AiServiceException.from(e, "AI가 변경안을 만들지 못했습니다. 잠시 후 다시 요청해 주세요.");
        }
        if (response == null || !response.path("success").asBoolean() || !response.path("data").isObject())
            throw new IllegalStateException("문서 수정 서비스의 응답이 올바르지 않습니다");
        return response.get("data");
    }
    public JsonNode analyzeImpact(String systemPrompt, JsonNode payload) {
        var response = body(agent.post().uri("/api/agent/chat")
            .header("X-LLM-Provider", "azure-foundry").header("X-LLM-Model", model)
            .header(AiUsageScope.TRACKING_HEADER, AiUsageScope.active() ? "1" : "0")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("messages", List.of(Map.of("role", "user", "content", payload.toString())),
                "systemPrompt", systemPrompt))
            .retrieve().toEntity(JsonNode.class).timeout(JobTimeBudget.limit(Duration.ofSeconds(90))).block());
        if (response == null || !response.path("content").isTextual())
            throw new IllegalStateException("영향 분석 서비스의 응답이 올바르지 않습니다");
        return response;
    }
}
