package com.timiroom.infra.ragpipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class RagPipelineChatTimeoutTest {
    @Test void slowChatEndsWithFriendlyMessageInsteadOfServerError() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/chat/message",exchange->{
            try {Thread.sleep(3000);} catch(InterruptedException e) {Thread.currentThread().interrupt();}
            byte[] body="{\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            var client=new RagPipelineClient("http://127.0.0.1:"+server.getAddress().getPort(),"",1,new ObjectMapper());
            // 503으로 내려가고 화면에는 이 문구가 보인다(예전에는 500 "서버 내부 오류")
            assertThatThrownBy(()->client.chat(Map.of("message","hi"),Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("AI 응답이 오래 걸려");
        } finally { server.stop(0); }
    }
    @Test void detectsWrappedTimeouts() {
        assertThat(RagPipelineClient.causedByTimeout(new RuntimeException(new java.util.concurrent.TimeoutException()))).isTrue();
        assertThat(RagPipelineClient.causedByTimeout(new RuntimeException("other"))).isFalse();
    }
}
