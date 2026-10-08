package com.timiroom.infra.ragpipeline;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
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
            @Value("${document-impact.model:gpt-5.4-mini}") String model) {
        this.editor = webClient(editUrl);
        this.agent = webClient(agentUrl);
        this.model = model;
    }
    private static WebClient webClient(String url) {
        return WebClient.builder().baseUrl(url).codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024)).build();
    }
    public JsonNode edit(String docType, JsonNode document, String instruction) {
        var response = editor.post().uri("/api/v1/document/{type}/edit", docType)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("document", document, "instruction", instruction, "history", List.of()))
            .retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(240)).block();
        if (response == null || !response.path("success").asBoolean() || !response.path("data").isObject())
            throw new IllegalStateException("문서 수정 서비스의 응답이 올바르지 않습니다");
        return response.get("data");
    }
    public JsonNode analyzeImpact(String systemPrompt, JsonNode payload) {
        var response = agent.post().uri("/api/agent/chat")
            .header("X-LLM-Provider", "azure-foundry").header("X-LLM-Model", model)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("messages", List.of(Map.of("role", "user", "content", payload.toString())),
                "systemPrompt", systemPrompt))
            .retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(90)).block();
        if (response == null || !response.path("content").isTextual())
            throw new IllegalStateException("영향 분석 서비스의 응답이 올바르지 않습니다");
        return response;
    }
}
