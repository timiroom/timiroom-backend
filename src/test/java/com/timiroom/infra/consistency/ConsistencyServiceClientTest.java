package com.timiroom.infra.consistency;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ConsistencyServiceClientTest {
    HttpServer server;
    ConsistencyServiceClient client;
    String path, key, body;
    String response;
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path = exchange.getRequestURI().getPath();
            key = exchange.getRequestHeaders().getFirst("X-Service-Key");
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        client = new ConsistencyServiceClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-service-key");
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void reusesArtifactReviewContractWithServiceAuthentication() {
        response = "{\"passed\":true,\"findings\":[]}";
        assertThat(client.reviewArtifacts(Map.of("artifacts", Map.of("PRD", Map.of()))).path("passed").asBoolean()).isTrue();
        assertThat(path).isEqualTo("/api/v1/artifacts/consistency/review");
        assertThat(key).isEqualTo("test-service-key");
        assertThat(body).contains("artifacts", "PRD");
    }
    @Test void reusesRevisionContract() {
        response = "{\"revisedContent\":{},\"changeSummary\":\"수정\"}";
        assertThat(client.reviseArtifact(Map.of("artifactType", "API_SPEC")).path("revisedContent").isObject()).isTrue();
        assertThat(path).isEqualTo("/api/v1/artifacts/revise");
    }
    @Test void rejectsMissingStructuredFindings() {
        response = "{\"passed\":true}";
        assertThatThrownBy(() -> client.reviewArtifacts(Map.of())).isInstanceOf(IllegalStateException.class);
    }
}
