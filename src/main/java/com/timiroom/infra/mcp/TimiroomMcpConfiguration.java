package com.timiroom.infra.mcp;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.integration.dto.IntegrationPrincipal;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import java.util.*;

@Configuration @ConditionalOnProperty(name="integration.enabled",havingValue="true")
public class TimiroomMcpConfiguration {
    @Bean HttpServletStatelessServerTransport timiroomTransport(ObjectMapper mapper) {
        // Every request can reach either replica; authentication and durable jobs carry state.
        return HttpServletStatelessServerTransport.builder().jsonMapper(new JacksonMcpJsonMapper(mapper)).messageEndpoint("/mcp")
            .contextExtractor(request->{
                if(!(request.getUserPrincipal() instanceof Authentication authentication) || !authentication.isAuthenticated()
                        || !(authentication.getPrincipal() instanceof OAuth2AuthenticatedPrincipal principal)) throw new SecurityException("AUTHENTICATION_REQUIRED");
                Number member=principal.getAttribute("member_id");String client=principal.getAttribute("client_id");String grant=principal.getAttribute("grant_id");
                Collection<String> scopes=principal.getAttribute("scope");
                var actor=new IntegrationPrincipal(member.longValue(),client,UUID.fromString(grant),Set.copyOf(scopes));
                return McpTransportContext.create(Map.of("actor",actor));
            }).build();
    }
    @Bean ServletRegistrationBean<?> timiroomMcpServlet(HttpServletStatelessServerTransport transport) {
        var servlet=new ServletRegistrationBean<>(transport,"/mcp");servlet.setAsyncSupported(true);return servlet;
    }
    @Bean(destroyMethod="close") McpStatelessSyncServer timiroomMcpServer(HttpServletStatelessServerTransport transport,TimiroomMcpTools tools,McpResultMapper results) {
        var specifications=tools.definitions().stream().map(definition->new McpStatelessServerFeatures.SyncToolSpecification(
            McpSchema.Tool.builder().name(definition.name()).description(definition.description()).inputSchema(definition.schema())
                .annotations(new McpSchema.ToolAnnotations(null,definition.readOnly(),false,true,!definition.readOnly(),false)).build(),
            (context,input)->{
                try {return results.success(tools.invoke(definition.name(),(IntegrationPrincipal)context.get("actor"),input.arguments()));}
                catch(RuntimeException error) {return results.error(error);}
            })).toList();
        return McpServer.sync(transport).serverInfo("timiroom","1.0.0").capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
            .tools(specifications).build();
    }
}
