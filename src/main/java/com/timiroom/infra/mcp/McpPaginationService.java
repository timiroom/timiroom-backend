package com.timiroom.infra.mcp;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.integration.dto.IntegrationPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

@Service @ConditionalOnProperty(name="integration.enabled",havingValue="true")
public class McpPaginationService {
    private final byte[] secret;
    private final ObjectMapper mapper;
    private final Clock clock;
    public McpPaginationService(@Value("${integration.cursor-secret}") String secret,ObjectMapper mapper,Clock clock) {
        this.secret=secret.getBytes(StandardCharsets.UTF_8);this.mapper=mapper;this.clock=clock;
        if(this.secret.length<32) throw new IllegalArgumentException("Cursor signing key must contain at least 32 bytes");
    }
    public record Page(String content,String nextCursor,boolean truncated,int totalChars,int offset) {}
    public int offset(IntegrationPrincipal actor,String binding,String cursor,int size) {return cursor==null?0:decode(actor,binding,cursor,size);}
    public String next(IntegrationPrincipal actor,String binding,int offset,int size) {return offset>=size?null:encode(actor,binding,offset);}
    public Page page(IntegrationPrincipal actor,String binding,String content,String cursor,int maxChars) {
        if(maxChars<2 || maxChars>24000) throw new IllegalArgumentException("INVALID_INPUT");
        int offset=0;
        if(cursor!=null) offset=decode(actor,binding,cursor,content.length());
        int end=Math.min(content.length(),offset+maxChars);
        if(end<content.length() && end>offset && Character.isHighSurrogate(content.charAt(end-1)) && Character.isLowSurrogate(content.charAt(end))) end--;
        String next=end<content.length()?encode(actor,binding,end):null;
        return new Page(content.substring(offset,end),next,next!=null,content.length(),offset);
    }
    private String encode(IntegrationPrincipal actor,String binding,int offset) {
        try {
            var payload=mapper.createObjectNode().put("memberId",actor.memberId()).put("clientId",actor.clientId())
                .put("grantId",actor.grantId().toString()).put("binding",binding).put("offset",offset)
                .put("expires",clock.instant().plusSeconds(900).getEpochSecond());
            var encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(payload));
            return encoded+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(encoded));
        } catch(java.io.IOException e) {throw new IllegalStateException("Cursor serialization failed",e);}
    }
    private int decode(IntegrationPrincipal actor,String binding,String cursor,int length) {
        try {
            if(cursor.length()>4096) throw new IllegalArgumentException();
            var parts=cursor.split("\\.",-1);if(parts.length!=2) throw new IllegalArgumentException();
            byte[] signature=Base64.getUrlDecoder().decode(parts[1]);
            if(!parts[1].equals(Base64.getUrlEncoder().withoutPadding().encodeToString(signature)) || !MessageDigest.isEqual(sign(parts[0]),signature))
                throw new IllegalArgumentException();
            var payload=mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if(payload.path("memberId").asLong()!=actor.memberId() || !actor.clientId().equals(payload.path("clientId").asText())
                    || !actor.grantId().toString().equals(payload.path("grantId").asText()) || !binding.equals(payload.path("binding").asText())
                    || payload.path("expires").asLong()<=clock.instant().getEpochSecond() || !payload.path("offset").isIntegralNumber())
                throw new IllegalArgumentException();
            int offset=payload.path("offset").asInt(-1);if(offset<0 || offset>=length) throw new IllegalArgumentException();return offset;
        } catch(java.io.IOException|IllegalArgumentException invalid) {throw new IllegalArgumentException("INVALID_CURSOR");}
    }
    private byte[] sign(String value) {
        try {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));}
        catch(java.security.GeneralSecurityException e) {throw new IllegalStateException("Cursor signing unavailable",e);}
    }
}
