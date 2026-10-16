package com.timiroom.infra.slack;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.handshake.*;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class SlackSocketCloseTest {
    @Test void lateFramesAreNotQueuedAfterShutdown() throws Exception {
        var config=new SlackConfig();config.getMethodsConfig().setStatsEnabled(false);
        var client=SlackSocketConfiguration.socketClient(Slack.getInstance(config),"xapp-fixture","ws://127.0.0.1:1");
        try {
            client.close();
            client.enqueueMessage("{\"type\":\"hello\"}");
            assertThat(client.getMessageQueue().poll()).isNull();
        } finally {client.close();config.close();}
    }
    @Test void shutdownCancelsAHandshakeAlreadyInFlight() throws Exception {
        var ready=new CountDownLatch(1);var handshaking=new CountDownLatch(1);
        var release=new CountDownLatch(1);var closed=new CountDownLatch(1);
        var server=new WebSocketServer(new InetSocketAddress("127.0.0.1",0)) {
            @Override public void onStart() {ready.countDown();}
            @Override public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket ws,Draft draft,ClientHandshake request) throws InvalidDataException {
                handshaking.countDown();
                try {release.await(5,TimeUnit.SECONDS);} catch(InterruptedException e) {Thread.currentThread().interrupt();}
                return super.onWebsocketHandshakeReceivedAsServer(ws,draft,request);
            }
            @Override public void onOpen(WebSocket ws,ClientHandshake handshake) {
                ws.send("{\"type\":\"hello\",\"connection_info\":{\"app_id\":\"A1\"}}");
            }
            @Override public void onMessage(WebSocket ws,String message) {}
            @Override public void onClose(WebSocket ws,int code,String reason,boolean remote) {closed.countDown();}
            @Override public void onError(WebSocket ws,Exception error) {}
        };
        server.start();assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
        var config=new SlackConfig();config.getMethodsConfig().setStatsEnabled(false);
        var client=SlackSocketConfiguration.socketClient(Slack.getInstance(config),"xapp-fixture","ws://127.0.0.1:"+server.getPort());
        try {
            client.connect();assertThat(handshaking.await(5,TimeUnit.SECONDS)).isTrue();
            client.close();release.countDown();
            assertThat(closed.await(3,TimeUnit.SECONDS)).as("No connection may survive shutdown").isTrue();
        } finally {release.countDown();client.close();server.stop(1000);config.close();}
    }
}
