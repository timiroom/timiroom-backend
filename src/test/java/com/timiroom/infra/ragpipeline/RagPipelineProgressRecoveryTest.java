package com.timiroom.infra.ragpipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RagPipelineProgressRecoveryTest {
    @Test
    void heartbeat_and_completion_cross_the_real_http_stream() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/orchestration/progress/run", exchange -> {
            byte[] body = ("event: heartbeat\ndata: {\"pipelineId\":\"run\"}\n\n"
                    + "event: complete\ndata: {\"result\":{\"storage\":{\"status\":\"stored\"}}}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var client = new RagPipelineClient("http://127.0.0.1:" + server.getAddress().getPort(), "", 5, new ObjectMapper());
            var events = client.getProgressFlux("run").collectList().block(Duration.ofSeconds(5));
            assertThat(events).hasSize(2);
            assertThat(events.get(0).event()).isEqualTo("heartbeat");
            assertThat(events.get(1).event()).isEqualTo("complete");
            assertThat(new ObjectMapper().readTree(events.get(1).data()).path("result").path("storage").path("status").asText()).isEqualTo("stored");
        } finally { server.stop(0); }
    }

    @Test
    void missed_failure_is_read_from_the_state_endpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/orchestration/state/run", exchange -> {
            byte[] body = "{\"success\":true,\"data\":{\"status\":\"FAILED\",\"error\":{\"message\":\"validation failed\"}}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var client = new RagPipelineClient("http://127.0.0.1:" + server.getAddress().getPort(), "", 5, new ObjectMapper());
            assertThat(client.progressState("run")).containsEntry("status", "FAILED").containsKey("error");
        } finally { server.stop(0); }
    }
}
