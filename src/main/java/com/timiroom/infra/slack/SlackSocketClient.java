package com.timiroom.infra.slack;

import com.slack.api.Slack;
import com.slack.api.socket_mode.impl.SocketModeClientJavaWSImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolates URL refresh from the ordered message/ACK processor. */
final class SlackSocketClient extends SocketModeClientJavaWSImpl {
    private static final Logger status=LoggerFactory.getLogger(SlackSocketClient.class);
    private final ExecutorService refresh=Executors.newSingleThreadExecutor(
        Thread.ofPlatform().daemon().name("slack-refresh-",0).factory());
    private final AtomicBoolean refreshing=new AtomicBoolean();
    private volatile boolean closed;

    SlackSocketClient(Slack slack,String token,String url) throws URISyntaxException {super(slack,token,url,1);}

    @Override public void connectToNewEndpoint() {
        if(closed || !refreshing.compareAndSet(false,true)) return;
        try {
            refresh.execute(()->{
                try {
                    if(!closed) super.connectToNewEndpoint();
                } catch(Exception unavailable) {
                    // SDK throws a RuntimeException for HTTP 200 {ok:false}. Never let it
                    // cancel the periodic monitor, and never log its credential-bearing URL.
                    if(!closed) status.warn("SLACK_SOCKET_REFRESH_FAILED; monitor will retry");
                } finally {refreshing.set(false);}
            });
        } catch(RejectedExecutionException stopped) {refreshing.set(false);}
    }
    @Override public synchronized void connect() {if(!closed) super.connect();}
    @Override public synchronized void enqueueMessage(String message) {
        if(closed) {
            // A connection started before shutdown can finish its handshake later.
            super.disconnect();
            return;
        }
        super.enqueueMessage(message);
    }
    @Override public synchronized void close() throws IOException {
        closed=true;setAutoReconnectEnabled(false);refresh.shutdownNow();super.close();
    }
    @Override public Logger getLogger() {return org.slf4j.helpers.NOPLogger.NOP_LOGGER;}
}
