package com.timiroom.infra.slack;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

/** Verifies the exact bytes decoded as UTF-8, before form parsing. */
public final class SlackRequestVerifier {
    private final String secret;
    private final Clock clock;
    public SlackRequestVerifier(String secret,Clock clock) {
        if(secret==null || secret.isBlank()) throw new IllegalArgumentException("Slack signing secret required");
        this.secret=secret;this.clock=clock;
    }
    public void verify(String timestamp,String signature,String body) {
        try {
            long seconds=Long.parseLong(timestamp),now=clock.instant().getEpochSecond();
            if(seconds<now-300 || seconds>now+300 || signature==null || !signature.matches("v0=[0-9a-f]{64}")
                || body==null || body.length()>16000) throw new SecurityException("SLACK_SIGNATURE_INVALID");
            var mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            byte[] expected=mac.doFinal(("v0:"+timestamp+":"+body).getBytes(StandardCharsets.UTF_8));
            if(!MessageDigest.isEqual(expected,HexFormat.of().parseHex(signature.substring(3))))
                throw new SecurityException("SLACK_SIGNATURE_INVALID");
        } catch(SecurityException e) { throw e; }
        catch(Exception e) { throw new SecurityException("SLACK_SIGNATURE_INVALID"); }
    }
}
