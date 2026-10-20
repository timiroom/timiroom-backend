package com.timiroom.infra.slack;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import java.time.Clock;

@Configuration
@ConditionalOnProperty(name={"integration.enabled","integration.slack.enabled"},havingValue="true")
public class SlackConfiguration {
    @Bean @Order(-1)
    SecurityFilterChain slackOidcCallbackChain(HttpSecurity http) throws Exception {
        // Only this relay is public; it never links an account or consumes a state.
        return http.securityMatcher("/integrations/slack/oauth/callback")
            .csrf(csrf->csrf.disable()).requestCache(cache->cache.disable())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers(org.springframework.http.HttpMethod.POST,
                "/integrations/slack/oauth/callback").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/integrations/slack/oauth/callback").permitAll()
                .anyRequest().denyAll()).build();
    }
    @Bean(name="slackScheduler") org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler slackScheduler() {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("timiroom-slack-");return scheduler;
    }
    @Bean SlackRequestVerifier slackVerifier(@Value("${integration.slack.signing-secret}") String secret) {
        return new SlackRequestVerifier(secret,Clock.systemUTC());
    }
    @Bean @Order(0)
    SecurityFilterChain slackCommandChain(HttpSecurity http) throws Exception {
        // This exact callback authenticates with the raw-body signature, never a browser cookie.
        return http.securityMatcher("/integrations/slack/commands")
            .csrf(csrf->csrf.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers(org.springframework.http.HttpMethod.POST,"/integrations/slack/commands").permitAll()
                .anyRequest().denyAll()).build();
    }
}
