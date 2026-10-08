package com.timiroom.domain.integration.controller;
import com.timiroom.domain.integration.dto.IntegrationScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @ConditionalOnProperty(name="integration.enabled",havingValue="true")
public class IntegrationMetadataController {
    @Value("${integration.issuer:http://localhost:8080}") private String issuer;
    @GetMapping({"/.well-known/oauth-protected-resource","/.well-known/oauth-protected-resource/mcp"})
    public Map<String,Object> metadata() {
        return Map.of("resource",issuer+"/mcp","resource_name","Timiroom","authorization_servers",List.of(issuer),
            "scopes_supported",Arrays.stream(IntegrationScope.values()).map(IntegrationScope::value).toList(),"bearer_methods_supported",List.of("header"));
    }
}
