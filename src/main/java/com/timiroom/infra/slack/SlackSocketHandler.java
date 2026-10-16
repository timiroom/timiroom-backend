package com.timiroom.infra.slack;

import com.timiroom.domain.spec.service.DocumentHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Accepts envelopes only from the authenticated, app-validated Socket Mode connection. */
public final class SlackSocketHandler {
    static final String RETRY="요청이 많습니다. 잠시 후 명령을 다시 실행하세요.";
    private static final Logger log=LoggerFactory.getLogger(SlackSocketHandler.class);
    private final String team,app;
    private final SlackCommandService commands;
    private final SlackWebClient web;
    private final Executor executor;
    private final Semaphore capacity=new Semaphore(64);

    public SlackSocketHandler(String team,String app,SlackCommandService commands,SlackWebClient web,Executor executor) {
        this.team=team;this.app=app;this.commands=commands;this.web=web;this.executor=executor;
    }

    public void receive(String envelopeId,Map<String,String> payload,Consumer<Map<String,Object>> acknowledge) {
        if(envelopeId==null || !envelopeId.matches("[A-Za-z0-9-]{1,128}")) return;
        final SlackCommand command;
        try {
            var fields=new HashMap<>(payload);
            // Slack's documented socket slash payload may omit api_app_id. The socket hello
            // has already authenticated the app; a present but different payload app is rejected.
            fields.putIfAbsent("api_app_id",app);
            String body=fields.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue()))
                .collect(Collectors.joining("&"));
            command=SlackCommand.from(body,team,app);
            if(SlackCommand.parse(command.text()).action()==SlackCommand.Action.HELP) {
                acknowledge.accept(ack(envelopeId,SlackCommandService.HELP));return;
            }
        } catch(SecurityException invalid) {
            acknowledge.accept(ack(envelopeId,"Slack 앱과 워크스페이스 설정을 확인하세요."));return;
        } catch(IllegalArgumentException invalid) {
            acknowledge.accept(ack(envelopeId,SlackCommandService.HELP));return;
        }
        if(!capacity.tryAcquire()) {acknowledge.accept(ack(envelopeId,RETRY));return;}
        try {
            // ACK never waits for database locks, the AI engine, or Web API delivery.
            acknowledge.accept(Map.of("envelope_id",envelopeId));
            executor.execute(()->{
                try {process(command,DocumentHash.of("socket:"+envelopeId));}
                finally {capacity.release();}
            });
        } catch(RuntimeException failure) {capacity.release();throw failure;}
    }

    private void process(SlackCommand command,String requestId) {
        String text;
        try {text=commands.accept(command,requestId).get("text");}
        catch(SecurityException denied) {text="티미룸 계정 연결과 프로젝트 채널·권한을 확인하세요. 계정 연결은 /timiroom connect로 시작합니다.";}
        catch(IllegalArgumentException invalid) {text=SlackCommandService.HELP;}
        catch(RuntimeException unavailable) {text="요청을 접수하지 못했습니다. 잠시 후 다시 시도하세요.";}
        try {web.reply(command.channel(),command.user(),text);}
        catch(RuntimeException delivery) {
            // Never log the payload, connection code, response URL, token, or SDK exception.
            log.warn("SLACK_SOCKET_REPLY_FAILED request={}",requestId);
        }
    }
    private static Map<String,Object> ack(String id,String text) {
        return Map.of("envelope_id",id,"payload",Map.of("response_type","ephemeral","text",text));
    }
    private static String encode(String value) {return URLEncoder.encode(Objects.requireNonNullElse(value,""),StandardCharsets.UTF_8);}
}
