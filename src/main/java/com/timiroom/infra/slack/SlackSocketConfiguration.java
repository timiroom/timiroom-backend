package com.timiroom.infra.slack;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.socket_mode.impl.SocketModeClientJavaWSImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.*;

@Configuration
@ConditionalOnProperty(name={"integration.enabled","integration.slack.enabled","integration.slack.socket-mode.enabled"},havingValue="true")
public class SlackSocketConfiguration {
    @Bean SlackSocketConnection slackSocketConnection(
        @Value("${integration.slack.team-id}") String team,
        @Value("${integration.slack.app-id}") String app,
        @Value("${integration.slack.app-token}") String token,
        SlackCommandService commands,SlackWebClient web) {
        if(token==null || !token.matches("xapp-[A-Za-z0-9-]{20,}"))
            throw new IllegalArgumentException("Slack app token required for Socket Mode");
        var config=new SlackConfig();
        config.setHttpClientCallTimeoutMillis(5000);
        config.setPrettyResponseLoggingEnabled(false);
        config.getHttpClientResponseHandlers().clear();
        // App-level tokens cannot call auth.test, which SDK per-team statistics uses.
        config.getMethodsConfig().setStatsEnabled(false);
        var slack=Slack.getInstance(config);
        var workers=Executors.newFixedThreadPool(2,Thread.ofPlatform().daemon().name("slack-command-",0).factory());
        var timer=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("slack-connect-",0).factory());
        var handler=new SlackSocketHandler(team,app,commands,web,workers);
        return new SlackSocketConnection(app,()->{
            var opened=slack.methods(token).appsConnectionsOpen(r->r);
            if(!opened.isOk() || opened.getUrl()==null) throw new IllegalStateException("SLACK_SOCKET_URL_UNAVAILABLE");
            var uri=java.net.URI.create(opened.getUrl());
            if(!"wss".equals(uri.getScheme()) || uri.getHost()==null || !uri.getHost().endsWith(".slack.com")
                || uri.getUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=443))
                throw new IllegalStateException("SLACK_SOCKET_URL_INVALID");
            // The SDK owns reconnect/ping/refresh. Suppress its raw protocol/error dumps;
            // the lifecycle adapter emits safe operational status without tokens or URLs.
            return socketClient(slack,token,opened.getUrl());
        },handler,timer,workers);
    }
    static SocketModeClientJavaWSImpl socketClient(Slack slack,String token,String url) throws java.net.URISyntaxException {
        return new SlackSocketClient(slack,token,url);
    }
}
