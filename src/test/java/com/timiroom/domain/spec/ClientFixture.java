package com.timiroom.domain.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.config.IntegrationAuthorizationConfig;
import com.timiroom.domain.integration.service.*;
import com.timiroom.domain.integration.entity.IntegrationGrant;
import com.timiroom.domain.integration.repository.IntegrationGrantRepository;
import com.timiroom.domain.integration.controller.*;
import com.timiroom.domain.member.entity.Member;
import com.timiroom.domain.member.enums.Provider;
import com.timiroom.domain.member.repository.MemberRepository;
import com.timiroom.domain.project.entity.Project;
import com.timiroom.domain.project.service.ProjectService;
import com.timiroom.domain.project.repository.ProjectMemberRepository;
import com.timiroom.domain.github.*;
import com.timiroom.domain.spec.service.*;
import com.timiroom.domain.spec.dto.*;
import com.timiroom.domain.graph.service.GraphCalculator;
import com.timiroom.domain.integrationjob.service.*;
import com.timiroom.domain.integrationjob.repository.IntegrationJobRepository;
import com.timiroom.infra.mcp.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType.*;

/** Loopback-only test executable. Not in production source or bootJar. No real provider calls. */
@org.springframework.boot.test.context.TestConfiguration
@EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration.class)
@EntityScan(basePackageClasses=IntegrationGrant.class)
@EnableJpaRepositories(basePackageClasses=IntegrationGrantRepository.class)
@Import({IntegrationAuthorizationConfig.class,IntegrationActorResolver.class,IntegrationAccessService.class,
    IntegrationMetadataController.class,IntegrationConnectionController.class,TimiroomMcpConfiguration.class,
    TimiroomMcpTools.class,McpResultMapper.class,McpPaginationService.class,ClientFixture.Login.class})
public class ClientFixture {
    static final UUID SNAPSHOT=UUID.fromString("12345678-1234-1234-1234-123456789abc");
    public static void main(String[] args) throws Exception {
        if(!"true".equals(System.getenv("TIMIROOM_TEST_POSTGRES"))) throw new IllegalStateException("Test database flag required");
        String schema="timiroom_client_"+UUID.randomUUID().toString().replace("-","");
        String url="jdbc:postgresql://127.0.0.1:55439/timiroom_integration_test";
        try(var c=java.sql.DriverManager.getConnection(url,"timiroom_test","");var s=c.createStatement()) {
            try(var r=s.executeQuery("select current_database()")){r.next();if(!r.getString(1).equals("timiroom_integration_test")) throw new IllegalStateException();}
            s.execute("create schema "+schema);
        }
        var application=new SpringApplication(ClientFixture.class);
        var settings=Map.ofEntries(
            Map.entry("server.address","127.0.0.1"),Map.entry("server.port","56380"),Map.entry("integration.enabled","true"),
            Map.entry("integration.issuer","http://127.0.0.1:56380"),Map.entry("integration.cursor-secret","fixture-only-key-at-least-32-bytes"),
            Map.entry("spring.datasource.url",url+"?currentSchema="+schema),Map.entry("spring.datasource.username","timiroom_test"),Map.entry("spring.datasource.password",""),
            Map.entry("spring.jpa.hibernate.ddl-auto","none"),Map.entry("spring.flyway.schemas",schema),Map.entry("spring.flyway.default-schema",schema),
            Map.entry("spring.data.redis.host","127.0.0.1"),Map.entry("spring.data.redis.port","56379"),Map.entry("spring.data.redis.password",""),
            Map.entry("spring.session.redis.namespace",schema),Map.entry("spring.session.timeout","30m"),Map.entry("frontend.url","http://127.0.0.1:56383")
        );
        // application.yml defaults have higher priority than defaultProperties; promote explicit test settings.
        application.run(settings.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new));
    }
    @Bean ObjectMapper objectMapper() {return new ObjectMapper().findAndRegisterModules();}
    @Bean MemberRepository members() {
        var repository=mock(MemberRepository.class);var member=Member.createOAuth("fixture","fixture@example.invalid",Provider.GOOGLE,"fixture-sub");
        org.springframework.test.util.ReflectionTestUtils.setField(member,"memberId",2L);
        when(repository.findByProviderAndProviderId(Provider.GOOGLE,"fixture-sub")).thenReturn(Optional.of(member));return repository;
    }
    @Bean DocumentAccessService access() {
        var access=mock(DocumentAccessService.class);when(access.requireRead(7L,2L)).thenReturn(Project.builder().projectId(7L).projectName("검증 프로젝트").teamId(1L).build());return access;
    }
    @Bean ProjectService projects() {
        var service=mock(ProjectService.class);when(service.getMyProjects(2L)).thenReturn(List.of(Project.builder().projectId(7L).projectName("검증 프로젝트").teamId(1L).build()));return service;
    }
    @Bean SpecSnapshotService snapshots() {
        var service=mock(SpecSnapshotService.class);
        String original="{\"title\":\"클라이언트 검증\",\"한국어\":\"한글과 😀\",\"endpoints\":[]}";
        var snapshot=new SpecSnapshotDto(SNAPSHOT,7L,1,2L,Instant.parse("2026-10-08T00:00:00Z"),List.of(new SpecDocumentDto(API_SPEC,10L,1L,1,DocumentHash.of(original),original)));
        when(service.latest(7L,2L)).thenReturn(snapshot);when(service.get(7L,2L,SNAPSHOT)).thenReturn(snapshot);return service;
    }
    @Bean SpecChangeService changes(){return mock(SpecChangeService.class);}
    @Bean GraphCalculator graph(ObjectMapper mapper){return new GraphCalculator(mapper);}
    @Bean IntegrationTaskService tasks(){return mock(IntegrationTaskService.class);}
    @Bean IntegrationJobService jobs(){return mock(IntegrationJobService.class);}
    @Bean IntegrationJobRepository jobRepository(){return mock(IntegrationJobRepository.class);}
    @Bean ProjectRepoLinkRepository links(){return mock(ProjectRepoLinkRepository.class);}
    @Bean GithubRepoRepository repos(){return mock(GithubRepoRepository.class);}
    @Bean ProjectMemberRepository projectMembers(){return mock(ProjectMemberRepository.class);}
    @Bean PullRequestConsistencyService reviews(){return mock(PullRequestConsistencyService.class);}
    @Bean @Order(10) SecurityFilterChain fixtureChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a->a.requestMatchers("/fixture/login","/auth/me").permitAll().anyRequest().authenticated()).build();
    }
    @org.springframework.boot.test.context.TestComponent
    @RestController static class Login {
        @GetMapping("/fixture/login") Map<String,Object> login(HttpServletRequest request) {
            var authorities=List.of(new SimpleGrantedAuthority("ROLE_USER"));
            var user=new DefaultOAuth2User(authorities,Map.of("sub","fixture-sub"),"sub");
            var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new OAuth2AuthenticationToken(user,authorities,"google"));
            request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
            request.getSession().setAttribute("memberId",2L);return Map.of("fixture",true);
        }
        @GetMapping("/auth/me") Map<String,Object> me(HttpServletRequest request) {return Map.of("memberId",2,"name","검증 사용자");}
    }
}
