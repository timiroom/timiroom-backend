package com.timiroom.infra.slack;

import com.google.gson.JsonParser;
import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.socket_mode.impl.SocketModeClientJavaWSImpl;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlackSocketWireTest {
    @ParameterizedTest @ValueSource(booleans={true,false})
    void realSocketAcknowledgesAndReopensAfterSlowOrFailedRefresh(boolean slowRefresh) throws Exception {
        var ready=new CountDownLatch(1);var connected=new CountDownLatch(1);
        var reconnected=new CountDownLatch(1);var acknowledged=new CountDownLatch(1);
        var refreshStarted=new CountDownLatch(1);var releaseRefresh=new CountDownLatch(1);
        var duringRefreshAck=new CountDownLatch(1);
        var first=new AtomicBoolean(true);var socket=new AtomicReference<WebSocket>();
        var failure=new AtomicReference<Exception>();
        var server=new WebSocketServer(new InetSocketAddress("127.0.0.1",0)) {
            @Override public void onStart() {ready.countDown();}
            @Override public void onOpen(WebSocket ws,ClientHandshake handshake) {
                socket.set(ws);
                ws.send("{\"type\":\"hello\",\"connection_info\":{\"app_id\":\"A1\"}}");
                if(first.getAndSet(false)) {
                    ws.send("{\"type\":\"slash_commands\",\"envelope_id\":\"wire-1\",\"accepts_response_payload\":true,\"payload\":{\"team_id\":\"T1\",\"user_id\":\"U1\",\"channel_id\":\"C1\",\"command\":\"/timiroom\",\"text\":\"help\"}}");
                    connected.countDown();
                } else reconnected.countDown();
            }
            @Override public void onMessage(WebSocket ws,String message) {
                var ack=JsonParser.parseString(message).getAsJsonObject();
                if("wire-1".equals(ack.get("envelope_id").getAsString()) &&
                    SlackCommandService.HELP.equals(ack.getAsJsonObject("payload").get("text").getAsString())) acknowledged.countDown();
                if("wire-2".equals(ack.get("envelope_id").getAsString())) duringRefreshAck.countDown();
            }
            @Override public void onClose(WebSocket ws,int code,String reason,boolean remote) {}
            @Override public void onError(WebSocket ws,Exception error) {failure.set(error);}
        };
        server.start();assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
        var api=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var refreshes=new AtomicInteger();
        String wsUrl="ws://127.0.0.1:"+server.getPort();
        api.createContext("/apps.connections.open",exchange->{
            int attempt=refreshes.incrementAndGet();
            refreshStarted.countDown();
            if(slowRefresh) {
                try {releaseRefresh.await(8,TimeUnit.SECONDS);} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
            }
            String body=!slowRefresh && attempt==1?"{\"ok\":false,\"error\":\"internal_error\"}":"{\"ok\":true,\"url\":\""+wsUrl+"\"}";
            byte[] response=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,response.length);
            try(var out=exchange.getResponseBody()) {out.write(response);}
        });
        api.start();
        var config=new SlackConfig();config.setMethodsEndpointUrlPrefix("http://127.0.0.1:"+api.getAddress().getPort()+"/");
        config.getHttpClientResponseHandlers().clear();
        config.getMethodsConfig().setStatsEnabled(false);
        var commands=mock(SlackCommandService.class);var web=mock(SlackWebClient.class);
        var connection=new SlackSocketConnection("A1",()->SlackSocketConfiguration.socketClient(Slack.getInstance(config),"xapp-fixture",wsUrl),
            new SlackSocketHandler("T1","A1",commands,web,Runnable::run),mock(ScheduledExecutorService.class),mock(ExecutorService.class));
        try {
            connection.start();connection.maintain();
            assertThat(connected.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(acknowledged.await(2,TimeUnit.SECONDS)).isTrue();
            if(slowRefresh) {
                socket.get().send("{\"type\":\"disconnect\",\"reason\":\"refresh_requested\"}");
                assertThat(refreshStarted.await(5,TimeUnit.SECONDS)).isTrue();
                socket.get().send("{\"type\":\"slash_commands\",\"envelope_id\":\"wire-2\",\"accepts_response_payload\":true,\"payload\":{\"team_id\":\"T1\",\"user_id\":\"U1\",\"channel_id\":\"C1\",\"command\":\"/timiroom\",\"text\":\"help\"}}");
                assertThat(duringRefreshAck.await(2,TimeUnit.SECONDS)).as("ACK must not wait for apps.connections.open").isTrue();
                releaseRefresh.countDown();
            } else socket.get().close(1001,"fixture transient outage");
            assertThat(reconnected.await(18,TimeUnit.SECONDS)).isTrue();
            assertThat(refreshes.get()).isGreaterThanOrEqualTo(slowRefresh?1:2);
            assertThat(failure.get()).isNull();verifyNoInteractions(commands,web);
        } finally {releaseRefresh.countDown();connection.stop();api.stop(0);server.stop(1000);config.close();}
    }
}
