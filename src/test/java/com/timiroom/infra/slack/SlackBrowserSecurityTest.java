package com.timiroom.infra.slack;

import com.timiroom.config.IntegrationAuthorizationConfig;
import com.timiroom.domain.integration.service.IntegrationActorResolver;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(SlackBrowserSecurityTest.Config.class)
class SlackBrowserSecurityTest {
    @Configuration @EnableWebMvc @EnableWebSecurity
    static class Config {
        @Bean @org.springframework.core.annotation.Order(-1) SecurityFilterChain relay(HttpSecurity http) throws Exception {
            return new SlackConfiguration().slackOidcCallbackChain(http);
        }
        @Bean @org.springframework.core.annotation.Order(3) SecurityFilterChain web(HttpSecurity http) throws Exception {
            return org.springframework.test.util.ReflectionTestUtils.invokeMethod(new IntegrationAuthorizationConfig(),"integrationWebChain",http);
        }
        @Bean SlackBrowserConnection flow(){return mock(SlackBrowserConnection.class);}
        @Bean IntegrationActorResolver actors(){var actors=mock(IntegrationActorResolver.class);when(actors.memberId(any())).thenReturn(7L);return actors;}
        @Bean SlackBrowserController controller(SlackBrowserConnection flow,IntegrationActorResolver actors){
            var controller=new SlackBrowserController(flow,actors);
            org.springframework.test.util.ReflectionTestUtils.setField(controller,"frontend","https://timiroom.kro.kr");return controller;
        }
    }
    @Autowired WebApplicationContext context;
    @Autowired SlackBrowserConnection flow;
    MockMvc mvc;
    @BeforeEach void setup(){reset(flow);mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();}
    @Test void connectRequiresLoginAndCsrf() throws Exception {
        mvc.perform(post("/api/v1/integrations/slack/connect").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/integrations/slack/connect").with(oauth2Login())).andExpect(status().isForbidden());
        verifyNoInteractions(flow);
        when(flow.start(eq(7L),any())).thenReturn("https://slack.com/openid/connect/authorize");
        mvc.perform(post("/api/v1/integrations/slack/connect").with(oauth2Login()).with(csrf())).andExpect(status().isOk());
    }
    @Test void callbackIsPublicButCompleteRequiresSessionLogin() throws Exception {
        mvc.perform(post("/integrations/slack/oauth/callback").contentType("application/x-www-form-urlencoded")
            .param("state","a".repeat(43)).param("code","code")).andExpect(status().isSeeOther());
        mvc.perform(get("/integrations/slack/oauth/complete").param("state","a".repeat(43)).param("code","code"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/integrations/slack/oauth/callback")).andExpect(status().isForbidden());
        verifyNoInteractions(flow);
    }
}
