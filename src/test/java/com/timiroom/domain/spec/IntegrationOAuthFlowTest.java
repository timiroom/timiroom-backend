package com.timiroom.domain.spec;

import com.timiroom.config.IntegrationAuthorizationConfig;
import com.timiroom.domain.integration.entity.IntegrationGrant;
import com.timiroom.domain.integration.repository.IntegrationGrantRepository;
import com.timiroom.domain.integration.service.IntegrationAccessService;
import com.timiroom.domain.integration.service.IntegrationActorResolver;
import com.timiroom.domain.member.repository.MemberRepository;
import com.timiroom.domain.member.entity.Member;
import com.timiroom.domain.member.enums.Provider;
import com.timiroom.domain.spec.service.DocumentAccessService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.util.UriComponentsBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(classes=IntegrationOAuthFlowTest.Fixture.class,properties={
    "integration.enabled=true","integration.issuer=http://127.0.0.1:56380",
    "spring.datasource.username=timiroom_test","spring.datasource.password=",
    "spring.datasource.driver-class-name=org.postgresql.Driver","spring.jpa.hibernate.ddl-auto=none",
    "spring.flyway.enabled=true","spring.session.store-type=none"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="TIMIROOM_TEST_POSTGRES",matches="true")
class IntegrationOAuthFlowTest {
    @Test void anonymousMcpLoginOffersBothExistingAccountProviders() throws Exception {
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var response=mvc.perform(get("/oauth2/authorize").queryParam("response_type","code").queryParam("client_id","timiroom-codex")
            .queryParam("redirect_uri",redirect).queryParam("scope","specs:read").queryParam("state","fixture-state")
            .queryParam("resource","http://127.0.0.1:56380/mcp").queryParam("code_challenge",challenge).queryParam("code_challenge_method","S256"))
            .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        assertThat(response.getRedirectedUrl()).endsWith("/integrations/login");
        mvc.perform(get("/integrations/login")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/oauth2/authorization/google")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("/oauth2/authorization/github")));
    }
    static final String URL="jdbc:postgresql://127.0.0.1:55439/timiroom_integration_test";
    static final String SCHEMA="timiroom_oauth_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) throws Exception {
        try(var c=java.sql.DriverManager.getConnection(URL,"timiroom_test","");var s=c.createStatement()){s.execute("create schema "+SCHEMA);}
        r.add("spring.datasource.url",()->URL+"?currentSchema="+SCHEMA);
        r.add("spring.flyway.schemas",()->SCHEMA);r.add("spring.flyway.default-schema",()->SCHEMA);
    }
    @Configuration @EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.session.SessionAutoConfiguration.class)
    @EntityScan(basePackageClasses=IntegrationGrant.class)
    @EnableJpaRepositories(basePackageClasses=IntegrationGrantRepository.class)
    @Import({IntegrationAuthorizationConfig.class,IntegrationAccessService.class,IntegrationActorResolver.class,
        com.timiroom.domain.integration.controller.IntegrationLoginController.class,BearerProbe.class})
    static class Fixture {}
    @org.springframework.web.bind.annotation.RestController
    static class BearerProbe {
        @org.springframework.web.bind.annotation.GetMapping("/mcp") String resource(){return "authenticated";}
    }
    @Autowired MockMvc mvc;
    @Autowired IntegrationGrantRepository grants;
    @MockitoBean DocumentAccessService access;
    @MockitoBean MemberRepository members;
    final ObjectMapper mapper=new ObjectMapper();
    final String verifier="test-verifier-abcdefghijklmnopqrstuvwxyz-0123456789";
    final String redirect="http://127.0.0.1:56381/callback";
    OAuth2AuthenticationToken user;
    @Test void metadataAdvertisesOnlySupportedPublicClientFlow() throws Exception {
        mvc.perform(get("/.well-known/oauth-authorization-server"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.authorization_response_iss_parameter_supported").value(true))
            .andExpect(jsonPath("$.token_endpoint_auth_methods_supported[0]").value("none"))
            .andExpect(jsonPath("$.grant_types_supported.length()").value(2))
            .andExpect(jsonPath("$.jwks_uri").doesNotExist()).andExpect(jsonPath("$.device_authorization_endpoint").doesNotExist());
    }
    @Test void invalidAccessTokenIsAuthenticationFailureRatherThanServerFailure() throws Exception {
        mvc.perform(post("/mcp").header("Authorization","Bearer invalid-fixture-token"))
            .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate",org.hamcrest.Matchers.containsString("resource_metadata=")));
    }
    @Test void wrongVerifierAndWrongClientCannotRedeemCode() throws Exception {
        String code=authorize();
        mvc.perform(post("/oauth2/token").param("grant_type","authorization_code").param("client_id","timiroom-claude")
            .param("code",code).param("redirect_uri",redirect).param("code_verifier",verifier)).andExpect(status().isBadRequest());
        code=authorize();
        mvc.perform(post("/oauth2/token").param("grant_type","authorization_code").param("client_id","timiroom-codex")
            .param("code",code).param("redirect_uri",redirect).param("code_verifier","different-verifier-abcdefghijklmnopqrstuvwxyz"))
            .andExpect(status().isBadRequest());
    }
    @BeforeEach void fixture() {
        var member=Member.createOAuth("google-user","test@example.invalid",Provider.GOOGLE,"123");
        org.springframework.test.util.ReflectionTestUtils.setField(member,"memberId",2L);
        when(members.findByProviderAndProviderId(Provider.GOOGLE,"123")).thenReturn(Optional.of(member));
        var providerAuthority=new org.springframework.security.oauth2.core.user.OAuth2UserAuthority(Map.of("sub","123","numericProfile",42L,"privateProfile","not-to-persist"));
        var authorities=List.of(providerAuthority,new SimpleGrantedAuthority("ROLE_USER"));
        user=new OAuth2AuthenticationToken(new DefaultOAuth2User(authorities,Map.of("sub","123"),"sub"),authorities,"google");
    }
    String authorize() throws Exception {
        var challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var initial=mvc.perform(get("/oauth2/authorize").with(authentication(user))
            .queryParam("client_id","timiroom-codex").queryParam("response_type","code").queryParam("redirect_uri",redirect)
            .queryParam("scope","specs:read").queryParam("state","client-state").queryParam("code_challenge",challenge)
            .queryParam("code_challenge_method","S256").queryParam("resource","http://127.0.0.1:56380/mcp"))
            .andExpect(status().is3xxRedirection()).andReturn();
        String consentState=java.net.URLDecoder.decode(UriComponentsBuilder.fromUriString(initial.getResponse().getRedirectedUrl()).build().getQueryParams().getFirst("state"),StandardCharsets.UTF_8);
        var session=(MockHttpSession)initial.getRequest().getSession();
        var csrfRequest=new org.springframework.mock.web.MockHttpServletRequest();csrfRequest.setSession(session);
        var csrfResponse=new org.springframework.mock.web.MockHttpServletResponse();
        var csrfRepository=new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository();
        var csrfToken=csrfRepository.generateToken(csrfRequest);csrfRepository.saveToken(csrfToken,csrfRequest,csrfResponse);
        new org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler().handle(csrfRequest,csrfResponse,()->csrfToken);
        String masked=((org.springframework.security.web.csrf.CsrfToken)csrfRequest.getAttribute(org.springframework.security.web.csrf.CsrfToken.class.getName())).getToken();
        var approval=mvc.perform(post("/oauth2/authorize").with(authentication(user))
            .session(session).param("_csrf",masked)
            .param("client_id","timiroom-codex").param("state",consentState).param("scope","specs:read").param("project_id","1"))
            .andExpect(status().is3xxRedirection()).andReturn();
        var params=UriComponentsBuilder.fromUriString(approval.getResponse().getRedirectedUrl()).build().getQueryParams();
        assertThat(params.getFirst("state")).isEqualTo("client-state");
        assertThat(java.net.URLDecoder.decode(params.getFirst("iss"),StandardCharsets.UTF_8)).isEqualTo("http://127.0.0.1:56380");
        assertThat(params.getFirst("error")).isNull();
        return java.net.URLDecoder.decode(params.getFirst("code"),StandardCharsets.UTF_8);
    }
    @Test void codeIsPkceBoundSingleUseAndRefreshRotates() throws Exception {
        var code=authorize();
        var first=mvc.perform(post("/oauth2/token").param("grant_type","authorization_code").param("client_id","timiroom-codex")
            .param("code",code).param("redirect_uri",redirect).param("code_verifier",verifier))
            .andExpect(status().isOk()).andReturn();
        var token=mapper.readTree(first.getResponse().getContentAsString());
        assertThat(token.path("access_token").asText()).isNotBlank();
        assertThat(token.path("refresh_token").asText()).isNotBlank();
        mvc.perform(post("/oauth2/token").param("grant_type","authorization_code").param("client_id","timiroom-codex")
            .param("code",code).param("redirect_uri",redirect).param("code_verifier",verifier)).andExpect(status().isBadRequest());
        var rotated=mvc.perform(post("/oauth2/token").param("grant_type","refresh_token").param("client_id","timiroom-codex")
            .param("refresh_token",token.path("refresh_token").asText())).andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(rotated.getResponse().getContentAsString()).path("refresh_token").asText()).isNotEqualTo(token.path("refresh_token").asText());
        mvc.perform(post("/oauth2/token").param("grant_type","refresh_token").param("client_id","timiroom-codex")
            .param("refresh_token",token.path("refresh_token").asText())).andExpect(status().isBadRequest());
    }
    @Test void bearerResourceNeitherRotatesNorAcceptsTheBrowserSession() throws Exception {
        var first=mvc.perform(post("/oauth2/token").param("grant_type","authorization_code").param("client_id","timiroom-codex")
            .param("code",authorize()).param("redirect_uri",redirect).param("code_verifier",verifier))
            .andExpect(status().isOk()).andReturn();
        String token=mapper.readTree(first.getResponse().getContentAsString()).path("access_token").asText();
        var browserSession=new MockHttpSession(); String sessionId=browserSession.getId();
        var context=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();context.setAuthentication(user);
        browserSession.setAttribute("SPRING_SECURITY_CONTEXT",context);browserSession.setAttribute("browserMarker","preserved");
        mvc.perform(get("/mcp").session(browserSession).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertThat(browserSession.getId()).isEqualTo(sessionId);
        assertThat(browserSession.getAttribute("browserMarker")).isEqualTo("preserved");
        mvc.perform(get("/mcp").session(browserSession)).andExpect(status().isUnauthorized());
    }
    @Test void missingPkceCannotAuthorize() throws Exception {
        mvc.perform(get("/oauth2/authorize").with(authentication(user)).queryParam("client_id","timiroom-codex")
            .queryParam("response_type","code").queryParam("redirect_uri",redirect).queryParam("scope","specs:read").queryParam("state","x")
            .queryParam("resource","http://127.0.0.1:56380/mcp")).andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern(redirect+"?error=invalid_request*"));
    }
    @Test void consentPostRequiresCsrf() throws Exception {
        mvc.perform(post("/oauth2/authorize").with(authentication(user)).param("client_id","timiroom-codex")
            .param("state","test-state").param("scope","specs:read").param("project_id","1"))
            .andExpect(status().isForbidden());
    }
}
