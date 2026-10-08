package com.timiroom.domain.spec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.integration.dto.IntegrationPrincipal;
import com.timiroom.infra.mcp.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(classes=McpHttpProtocolTest.Fixture.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"integration.enabled=true","server.address=127.0.0.1"})
class McpHttpProtocolTest {
    @LocalServerPort int port;
    final ObjectMapper mapper=new ObjectMapper();final HttpClient client=HttpClient.newHttpClient();
    HttpResponse<String> send(String token,String session,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/mcp"))
            .header("Content-Type","application/json").header("Accept","application/json, text/event-stream")
            .header("Mcp-Protocol-Version","2025-06-18").POST(HttpRequest.BodyPublishers.ofString(body));
        if(token!=null) request.header("Authorization","Bearer "+token);
        if(session!=null) request.header("Mcp-Session-Id",session);
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void initializeListAndCallPreserveEveryRequestsAuthenticatedActor() throws Exception {
        var initialize=send("fixture-a",null,"""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"fixture","version":"1"}}}
            """);
        assertThat(initialize.statusCode()).isEqualTo(200);assertThat(initialize.body()).contains("timiroom","2025-06-18");
        var session=initialize.headers().firstValue("Mcp-Session-Id").orElseThrow();
        send("fixture-a",session,"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        var list=send("fixture-a",session,"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertThat(list.statusCode()).isEqualTo(200);assertThat(list.body()).contains("timiroom_read_spec","timiroom_start_consistency_check");
        var call=send("fixture-b",session,"{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"timiroom_get_project_context\",\"arguments\":{\"projectId\":1}}}");
        assertThat(call.statusCode()).isEqualTo(200);
        String payload=call.body().lines().filter(line->line.startsWith("data:")).map(line->line.substring(5).trim()).findFirst().orElse(call.body());
        assertThat(mapper.readTree(payload).path("result").path("structuredContent").path("data").path("actorId").asLong()).isEqualTo(3L);
        assertThat(send(null,session,"{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}").statusCode()).isEqualTo(401);
    }
    @Configuration @EnableAutoConfiguration(exclude={
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.session.SessionAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class,
        org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration.class})
    @Import(TimiroomMcpConfiguration.class)
    static class Fixture {
        @Bean ObjectMapper mapper() {return new ObjectMapper().findAndRegisterModules();}
        @Bean McpResultMapper results(ObjectMapper mapper) {return new McpResultMapper(mapper);}
        @Bean TimiroomMcpTools tools(ObjectMapper mapper) {
            var tools=mock(TimiroomMcpTools.class,CALLS_REAL_METHODS);
            doAnswer(call->mapper.createObjectNode().put("actorId",((IntegrationPrincipal)call.getArgument(1)).memberId()))
                .when(tools).invoke(anyString(),any(),anyMap());return tools;
        }
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            var authentication=new OncePerRequestFilter() {
                protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws java.io.IOException,ServletException {
                    var token=request.getHeader("Authorization");long member="Bearer fixture-a".equals(token)?2:"Bearer fixture-b".equals(token)?3:0;
                    if(member==0) {response.setStatus(401);return;}
                    var principal=new DefaultOAuth2AuthenticatedPrincipal(Map.of("member_id",member,"client_id","fixture","grant_id",new UUID(0,member).toString(),"scope",List.of("specs:read")),List.of());
                    var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));
                    SecurityContextHolder.setContext(context);try {chain.doFilter(request,response);} finally {SecurityContextHolder.clearContext();}
                }
            };
            return http.csrf(c->c.disable()).authorizeHttpRequests(a->a.anyRequest().authenticated()).addFilterBefore(authentication,AuthorizationFilter.class).build();
        }
    }
}
