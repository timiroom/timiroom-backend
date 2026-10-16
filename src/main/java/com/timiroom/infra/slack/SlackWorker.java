package com.timiroom.infra.slack;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name={"integration.enabled","integration.slack.enabled"},havingValue="true")
public class SlackWorker {
    private final SlackQueueService queue;
    private final SlackCommandService commands;
    private final SlackWebClient client;
    @Scheduled(fixedDelayString="${integration.slack.poll-ms:1000}",scheduler="slackScheduler")
    public void poll() {
        queue.claimCommand().ifPresent(request->{
            try {
                String text=commands.execute(request.command(),request.id());
                client.reply(request.command().channel(),request.command().user(),text);
                queue.commandDone(request.id(),null);
            } catch(Exception failure) {
                queue.commandDone(request.id(),failure instanceof SecurityException?"ACCESS_DENIED":"COMMAND_FAILED");
                try { client.reply(request.command().channel(),request.command().user(),"요청을 처리하지 못했습니다. 계정 연결·현재 권한·명령 인수를 티미룸에서 확인하세요."); }
                catch(Exception ignored) { /* Delivery errors never repeat paid work or expose provider details. */ }
            }
        });
        queue.collectNotifications();
        queue.claimNotification().ifPresent(notification->{
            try {
                queue.validateNotification(notification);client.notifyChannel(notification.channel(),notification.text());
                queue.notificationDone(notification.id(),null);
            } catch(Exception failure) {
                queue.notificationDone(notification.id(),failure instanceof SecurityException?"ACCESS_DENIED":"DELIVERY_UNKNOWN");
            }
        });
    }
}
