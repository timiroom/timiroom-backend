package com.timiroom.infra.slack;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.io.Serializable;
import java.security.SecureRandom;
import java.time.*;
import java.util.Base64;

@Service @RequiredArgsConstructor
@ConditionalOnProperty(name={"integration.enabled","integration.slack.enabled"},havingValue="true")
public class SlackBrowserConnection {
    static final String KEY=SlackBrowserConnection.class.getName()+".pending";
    record Pending(Long member,String state,String nonce,Instant expires) implements Serializable {}
    private final SlackOidcClient client;
    private final SlackAccountService accounts;
    private final Clock clock;
    public String start(Long member,HttpSession session) {
        if(!client.ready()) throw new IllegalStateException("SLACK_CONNECT_UNAVAILABLE");
        var pending=new Pending(member,random(),random(),clock.instant().plusSeconds(300));
        session.setAttribute(KEY,pending);
        return client.authorizationUrl(pending.state(),pending.nonce());
    }
    public String complete(Long member,HttpSession session,String state,String code,String error) {
        if(session==null) return "failed";
        Pending pending;
        synchronized(session) {
            pending=(Pending)session.getAttribute(KEY);
            if(pending==null || !pending.member().equals(member) || !pending.state().equals(state)) return "failed";
            session.removeAttribute(KEY);
        }
        if(!pending.expires().isAfter(clock.instant())) return "failed";
        if(error!=null) return "access_denied".equals(error)?"cancelled":"failed";
        if(code==null || code.isBlank() || code.length()>4096) return "failed";
        try {
            var identity=client.identity(code,pending.nonce());
            accounts.linkIdentity(member,identity.team(),identity.user());
            return "connected";
        } catch(org.springframework.dao.DataIntegrityViolationException | IllegalStateException e) {return "failed";}
          catch(SecurityException e) {return "failed";}
    }
    private static String random() {
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
