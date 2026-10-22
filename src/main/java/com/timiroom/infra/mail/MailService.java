package com.timiroom.infra.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 알림 메일. SMTP(spring.mail.host)를 설정하지 않으면 보내지 않고 로그만 남긴다.
 * 결제·사용량 처리 흐름을 막지 않도록 따로 보내고, 실패해도 예외를 밖으로 던지지 않는다.
 */
@Slf4j
@Service
public class MailService {

    private final ObjectProvider<JavaMailSender> senders;
    private final String from;

    public MailService(ObjectProvider<JavaMailSender> senders,
                       @Value("${notify.mail-from:Align-it <no-reply@timiroom.kro.kr>}") String from) {
        this.senders = senders;
        this.from = from;
    }

    public boolean isEnabled() {
        return senders.getIfAvailable() != null;
    }

    @Async
    public void send(String to, String subject, String body) {
        if (to == null || to.isBlank()) return;
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null) {
            log.info("메일 설정이 없어 알림을 보내지 않습니다 | subject: {}", subject);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            sender.send(message);
        } catch (RuntimeException e) {
            log.warn("알림 메일 발송 실패 | subject: {}, error: {}", subject, e.getMessage());
        }
    }
}
