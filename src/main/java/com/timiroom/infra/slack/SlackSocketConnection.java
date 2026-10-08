package com.timiroom.infra.slack;

import com.google.gson.JsonParser;
import com.slack.api.socket_mode.SocketModeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lifecycle wrapper: Slack outages must not prevent the main web server from starting. */
public final class SlackSocketConnection implements SmartLifecycle {
    @FunctionalInterface interface Factory {SocketModeClient open() throws Exception;}
    private static final Logger log=LoggerFactory.getLogger(SlackSocketConnection.class);
    private final String app;
    private final Factory factory;
    private final SlackSocketHandler handler;
    private final ScheduledExecutorService timer;
    private final ExecutorService workers;
    private volatile boolean running;
    private SocketModeClient client;

    SlackSocketConnection(String app,Factory factory,SlackSocketHandler handler,
                          ScheduledExecutorService timer,ExecutorService workers) {
        this.app=app;this.factory=factory;this.handler=handler;this.timer=timer;this.workers=workers;
    }
    @Override public synchronized void start() {
        if(running) return;
        running=true;
        timer.scheduleWithFixedDelay(this::maintain,0,10,TimeUnit.SECONDS);
    }
    synchronized void maintain() {
        if(!running || client!=null) return;
        SocketModeClient candidate=null;
        try {
            candidate=factory.open();
            var socket=candidate;
            var verified=new AtomicBoolean();
            socket.addWebSocketMessageListener(message->{
                try {
                    var frame=JsonParser.parseString(message).getAsJsonObject();
                    if(!"hello".equals(frame.get("type").getAsString())) return;
                    boolean matches=app.equals(frame.getAsJsonObject("connection_info").get("app_id").getAsString());
                    verified.set(matches);
                    if(matches) log.info("SLACK_SOCKET_CONNECTED");
                    else {
                        log.error("SLACK_SOCKET_APP_MISMATCH");
                        socket.setAutoReconnectEnabled(false);socket.disconnect();
                    }
                } catch(Exception malformed) {log.warn("SLACK_SOCKET_INVALID_HELLO");}
            });
            socket.addSlashCommandsEnvelopeListener(envelope->{
                if(!verified.get()) return;
                try {
                    var fields=new HashMap<String,String>();
                    var payload=envelope.getPayload().getAsJsonObject();
                    for(var field:payload.entrySet()) {
                        if(field.getValue().isJsonPrimitive() && field.getValue().getAsJsonPrimitive().isString())
                            fields.put(field.getKey(),field.getValue().getAsString());
                    }
                    handler.receive(envelope.getEnvelopeId(),fields,ack->{
                        try {socket.sendWebSocketMessage(socket.getGson().toJson(ack));}
                        catch(Exception sendFailure) {throw new IllegalStateException("SLACK_SOCKET_ACK_FAILED");}
                    });
                } catch(Exception rejected) {log.warn("SLACK_SOCKET_REQUEST_FAILED");}
            });
            socket.addWebSocketCloseListener((code,reason)->log.info("SLACK_SOCKET_DISCONNECTED code={}",code));
            socket.addWebSocketErrorListener(error->log.warn("SLACK_SOCKET_TRANSPORT_ERROR"));
            socket.connect();client=socket;
        } catch(Exception unavailable) {
            if(candidate!=null) close(candidate);
            log.warn("SLACK_SOCKET_CONNECT_FAILED; retry scheduled");
        }
    }
    @Override public synchronized void stop() {
        running=false;timer.shutdownNow();
        if(client!=null) {close(client);client=null;}
        workers.shutdown();
    }
    private static void close(SocketModeClient socket) {
        try {socket.close();} catch(Exception ignored) {log.warn("SLACK_SOCKET_CLOSE_FAILED");}
    }
    @Override public boolean isRunning() {return running;}
    @Override public int getPhase() {return Integer.MAX_VALUE;}
}
