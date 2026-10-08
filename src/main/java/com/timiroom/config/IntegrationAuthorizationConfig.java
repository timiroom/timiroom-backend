package com.timiroom.config;

import com.timiroom.domain.integration.dto.IntegrationScope;
import com.timiroom.domain.integration.repository.IntegrationGrantRepository;
import com.timiroom.domain.integration.service.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.*;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.authentication.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.resource.introspection.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.security.SecureRandom;

@Configuration @EnableScheduling
@ConditionalOnProperty(name="integration.enabled",havingValue="true")
public class IntegrationAuthorizationConfig {
    @Bean Clock integrationClock() {return Clock.systemUTC();}
    @Value("${integration.issuer:http://localhost:8080}") private String issuer;
    @Value("${integration.oauth.codex.redirect-uri:http://127.0.0.1:56381/callback}") private String codexRedirect;
    @Value("${integration.oauth.claude.redirect-uri:http://localhost:56382/callback}") private String claudeRedirect;
    @Bean AuthorizationServerSettings integrationAuthorizationSettings() {
        var uri=java.net.URI.create(issuer);
        if(uri.getHost()==null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getRawUserInfo()!=null || issuer.endsWith("/")
                || (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme())
                && Set.of("localhost","127.0.0.1","::1").contains(uri.getHost())))) throw new IllegalArgumentException("HTTPS issuer is required");
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }
    @Bean RegisteredClientRepository integrationClients() {
        return new InMemoryRegisteredClientRepository(client("timiroom-codex",codexRedirect),client("timiroom-claude",claudeRedirect));
    }
    private RegisteredClient client(String id,String redirect) {
        return RegisteredClient.withId(id).clientId(id).clientName(id)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUri(redirect).scopes(scopes->{for(var scope:IntegrationScope.values()) scopes.add(scope.value());})
            .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(true).build())
            .tokenSettings(TokenSettings.builder().accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                .authorizationCodeTimeToLive(Duration.ofSeconds(60)).accessTokenTimeToLive(Duration.ofMinutes(15))
                .refreshTokenTimeToLive(Duration.ofDays(30)).reuseRefreshTokens(false).build()).build();
    }
    @Bean OAuth2AuthorizationService integrationAuthorizations(JdbcTemplate jdbc,RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc,clients);
    }
    @Bean OAuth2AuthorizationConsentService integrationConsentService() {
        // Every new connection must choose projects and scopes; prior consent cannot silently widen it.
        return new OAuth2AuthorizationConsentService() {
            public void save(OAuth2AuthorizationConsent consent) {}
            public void remove(OAuth2AuthorizationConsent consent) {}
            public OAuth2AuthorizationConsent findById(String client,String principal) {return null;}
        };
    }
    @Bean OAuth2TokenGenerator<?> integrationTokens(IntegrationGrantRepository grants) {
        var access=new OAuth2AccessTokenGenerator();
        OAuth2TokenGenerator<OAuth2RefreshToken> refresh=context->{
            if(!OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) return null;
            var authorization=context.getAuthorization();
            if(authorization==null) throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
            var grant=grants.findByAuthorizationId(authorization.getId()).orElseThrow(()->new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT));
            var now=Instant.now();if(!grant.active(now)) throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
            byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
            return new OAuth2RefreshToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),now,grant.getExpiresAt());
        };
        return new DelegatingOAuth2TokenGenerator(access,refresh);
    }
    @Bean OpaqueTokenIntrospector integrationIntrospector(OAuth2AuthorizationService authorizations,
            RegisteredClientRepository clients,IntegrationGrantRepository grants) {
        return token->{
            var authorization=authorizations.findByToken(token,OAuth2TokenType.ACCESS_TOKEN);
            if(authorization==null || authorization.getAccessToken()==null || !authorization.getAccessToken().isActive())
                throw new BadOpaqueTokenException("Invalid access token");
            var grant=grants.findByAuthorizationId(authorization.getId()).orElseThrow(()->new BadOpaqueTokenException("Invalid grant"));
            var client=clients.findById(authorization.getRegisteredClientId());
            if(!grant.active(Instant.now()) || client==null || !client.getClientId().equals(grant.getClientId()))
                throw new BadOpaqueTokenException("Invalid grant");
            OAuth2AuthorizationRequest request=authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
            if(request==null || !(issuer+"/mcp").equals(request.getAdditionalParameters().get("resource")))
                throw new BadOpaqueTokenException("Invalid audience");
            var scopes=authorization.getAccessToken().getToken().getScopes();
            if(!grant.scopeSet().containsAll(scopes)) throw new BadOpaqueTokenException("Invalid scope");
            var attributes=new HashMap<String,Object>();attributes.put("sub",grant.getMemberId().toString());
            attributes.put("member_id",grant.getMemberId());attributes.put("client_id",grant.getClientId());
            attributes.put("grant_id",grant.getGrantId().toString());attributes.put("scope",List.copyOf(scopes));
            attributes.put("iss",issuer);attributes.put("aud",List.of(issuer+"/mcp"));
            return new DefaultOAuth2AuthenticatedPrincipal(grant.getMemberId().toString(),attributes,
                scopes.stream().<GrantedAuthority>map(s->new SimpleGrantedAuthority("SCOPE_"+s)).toList());
        };
    }
    @Bean @Order(1)
    SecurityFilterChain integrationAuthorizationChain(HttpSecurity http,RegisteredClientRepository clients,
            OAuth2AuthorizationService authorizations,IntegrationGrantRepository grants,IntegrationAccessService access,
            IntegrationActorResolver actors,JdbcTemplate jdbc,PlatformTransactionManager transactions) throws Exception {
        var configurer=OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(configurer.getEndpointsMatcher()).with(configurer,server->server
            .authorizationServerMetadataEndpoint(endpoint->endpoint.authorizationServerMetadataCustomizer(metadata->{
                metadata.scopes(scopes->{scopes.clear();for(var scope:IntegrationScope.values()) scopes.add(scope.value());});
                metadata.tokenEndpointAuthenticationMethods(methods->{methods.clear();methods.add("none");});
                metadata.grantTypes(types->{types.clear();types.add("authorization_code");types.add("refresh_token");});
                metadata.claim("authorization_response_iss_parameter_supported",true);
                metadata.claims(claims->List.of("jwks_uri","pushed_authorization_request_endpoint","device_authorization_endpoint",
                    "revocation_endpoint","revocation_endpoint_auth_methods_supported","introspection_endpoint",
                    "introspection_endpoint_auth_methods_supported","tls_client_certificate_bound_access_tokens","dpop_signing_alg_values_supported")
                    .forEach(claims::remove));
            }))
            .authorizationEndpoint(endpoint->endpoint.consentPage(issuer+"/integrations/oauth/consent").authenticationProviders(providers->{
                for(int i=0;i<providers.size();i++) {
                    var provider=providers.get(i);
                    if(provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider code) {
                        code.setAuthorizationConsentRequired(context->true);
                        code.setAuthenticationValidator(new OAuth2AuthorizationCodeRequestAuthenticationValidator().andThen(context->{
                            OAuth2AuthorizationCodeRequestAuthenticationToken request=context.getAuthentication();
                            if(!"S256".equals(request.getAdditionalParameters().get("code_challenge_method"))
                                    || !(issuer+"/mcp").equals(request.getAdditionalParameters().get("resource"))
                                    || request.getState()==null || request.getState().isBlank())
                                throw new OAuth2AuthorizationCodeRequestAuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST),request);
                        }));
                    }
                    if(provider instanceof OAuth2AuthorizationConsentAuthenticationProvider consent) {
                        consent.setAuthorizationConsentCustomizer(context->{
                            OAuth2AuthorizationConsentAuthenticationToken request=context.getAuthentication();
                            context.getAuthorizationConsent().authorities(authorities->authorities.clear());
                            if(request.getScopes().isEmpty() || "1".equals(request.getAdditionalParameters().get("cancel"))) return;
                            request.getScopes().forEach(context.getAuthorizationConsent()::scope);
                            Object selected=request.getAdditionalParameters().get("project_id");
                            var projects=new LinkedHashSet<Long>();
                            try {
                                if(selected instanceof String value) projects.add(Long.valueOf(value));
                                else if(selected instanceof String[] values) for(var value:values) projects.add(Long.valueOf(value));
                                else throw new IllegalArgumentException();
                                access.create(actors.memberId((Authentication)request.getPrincipal()),context.getRegisteredClient().getClientId(),
                                    context.getAuthorization().getId(),projects,request.getScopes());
                            } catch(IllegalArgumentException|SecurityException invalid) {throw new OAuth2AuthenticationException(OAuth2ErrorCodes.ACCESS_DENIED);}
                        });
                        providers.set(i,transactional(provider,jdbc,authorizations,grants,transactions));
                    }
                }
            }))
            .clientAuthentication(endpoint->endpoint.authenticationConverter(request->{
                if(!"refresh_token".equals(request.getParameter("grant_type"))) return null;
                if(request.getParameter("client_secret")!=null || request.getHeader("Authorization")!=null
                        || !single(request,"client_id") || !single(request,"refresh_token")) throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
                return new OAuth2ClientAuthenticationToken(request.getParameter("client_id"),ClientAuthenticationMethod.NONE,null,
                    Map.of("refresh_token",request.getParameter("refresh_token")));
            }).authenticationProviders(providers->providers.add(0,new AuthenticationProvider() {
                public boolean supports(Class<?> type){return OAuth2ClientAuthenticationToken.class.isAssignableFrom(type);}
                public Authentication authenticate(Authentication authentication) {
                    var token=(OAuth2ClientAuthenticationToken)authentication;
                    Object refresh=token.getAdditionalParameters().get("refresh_token");
                    if(!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod()) || refresh==null) return null;
                    var client=clients.findByClientId(token.getPrincipal().toString());
                    var authorization=authorizations.findByToken(refresh.toString(),OAuth2TokenType.REFRESH_TOKEN);
                    if(client==null || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
                            || authorization==null || !client.getId().equals(authorization.getRegisteredClientId())
                            || authorization.getRefreshToken()==null || !authorization.getRefreshToken().isActive())
                        throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
                    return new OAuth2ClientAuthenticationToken(client,ClientAuthenticationMethod.NONE,null);
                }
            })))
            .tokenEndpoint(endpoint->endpoint.authenticationProviders(providers->{
                for(int i=0;i<providers.size();i++) if(providers.get(i) instanceof OAuth2AuthorizationCodeAuthenticationProvider
                        || providers.get(i) instanceof OAuth2RefreshTokenAuthenticationProvider)
                    providers.set(i,transactional(providers.get(i),jdbc,authorizations,grants,transactions));
            })));
        http.authorizeHttpRequests(a->a.anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/github")))
            .addFilterAfter(normalize(actors),SecurityContextHolderFilter.class)
            .addFilterAfter(new AuthorizationIssuerFilter(clients,issuer),SecurityContextHolderFilter.class);
        // The AS configurer excludes its endpoints from the default CSRF filter; protect consent POST explicitly.
        var csrf=new CsrfFilter(new HttpSessionCsrfTokenRepository());
        csrf.setBeanName("integrationConsentCsrfFilter");
        csrf.setRequireCsrfProtectionMatcher(request->"POST".equals(request.getMethod())
            && (request.getContextPath()+"/oauth2/authorize").equals(request.getRequestURI()));
        http.addFilterBefore(csrf,LogoutFilter.class);
        return http.build();
    }
    private boolean single(HttpServletRequest request,String name) {
        var values=request.getParameterValues(name);return values!=null && values.length==1 && !values[0].isBlank();
    }
    private AuthenticationProvider transactional(AuthenticationProvider delegate,JdbcTemplate jdbc,OAuth2AuthorizationService authorizations,
            IntegrationGrantRepository grants,PlatformTransactionManager transactions) {
        var template=new TransactionTemplate(transactions);
        return new AuthenticationProvider() {
            public boolean supports(Class<?> type){return delegate.supports(type);}
            public Authentication authenticate(Authentication authentication) {
                return template.execute(status->{
                    String value=null;OAuth2TokenType type=null;Map<String,Object> additional=Map.of();
                    if(authentication instanceof OAuth2AuthorizationCodeAuthenticationToken code){value=code.getCode();type=new OAuth2TokenType("code");additional=code.getAdditionalParameters();}
                    if(authentication instanceof OAuth2RefreshTokenAuthenticationToken refresh){value=refresh.getRefreshToken();type=OAuth2TokenType.REFRESH_TOKEN;additional=refresh.getAdditionalParameters();}
                    if(value!=null) {
                        var authorization=authorizations.findByToken(value,type);
                        if(authorization==null) throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
                        jdbc.queryForObject("select id from oauth2_authorization where id=? for update",String.class,authorization.getId());
                        var grant=grants.findByAuthorizationId(authorization.getId()).orElseThrow(()->new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT));
                        if(!grant.active(Instant.now()) || (additional.containsKey("resource") && !(issuer+"/mcp").equals(additional.get("resource"))))
                            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
                    }
                    return delegate.authenticate(authentication);
                });
            }
        };
    }
    private OncePerRequestFilter normalize(IntegrationActorResolver actors) {
        return new OncePerRequestFilter() {
            protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
                var previous=SecurityContextHolder.getContext();var authentication=previous.getAuthentication();
                if(authentication instanceof OAuth2AuthenticationToken oauth) {
                    // Persist only the verified identity, avoiding provider profile data and polymorphic numeric values.
                    var attributes=new HashMap<String,Object>();attributes.put("timiroomMemberId",actors.memberId(oauth).toString());
                    String identityKey="google".equals(oauth.getAuthorizedClientRegistrationId())?"sub":"id";
                    attributes.put(identityKey,Objects.requireNonNull(oauth.getPrincipal().getAttribute(identityKey)).toString());
                    var authorities=oauth.getAuthorities().stream().map(a->new org.springframework.security.core.authority.SimpleGrantedAuthority(a.getAuthority())).toList();
                    var normalized=new OAuth2AuthenticationToken(new DefaultOAuth2User(authorities,attributes,"timiroomMemberId"),authorities,oauth.getAuthorizedClientRegistrationId());
                    var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(normalized);SecurityContextHolder.setContext(context);
                }
                try {chain.doFilter(request,response);} finally {SecurityContextHolder.setContext(previous);}
            }
        };
    }
    @Bean @Order(2)
    SecurityFilterChain integrationResourceChain(HttpSecurity http,OpaqueTokenIntrospector introspector) throws Exception {
        return http.securityMatcher("/mcp","/.well-known/oauth-protected-resource","/.well-known/oauth-protected-resource/mcp")
            .cors(org.springframework.security.config.Customizer.withDefaults())
            .csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/.well-known/**").permitAll().anyRequest().authenticated())
            .oauth2ResourceServer(oauth->oauth.opaqueToken(t->t.introspector(introspector)).authenticationEntryPoint((request,response,error)->{
                response.setStatus(401);response.setHeader("WWW-Authenticate","Bearer resource_metadata=\""+issuer+"/.well-known/oauth-protected-resource/mcp\"");
            })).build();
    }
    @Bean @Order(3)
    SecurityFilterChain integrationWebChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/integrations/**","/api/v1/integrations/**","/api/v1/projects/*/spec-snapshots/**","/api/v1/projects/*/spec-changes/**")
            .cors(org.springframework.security.config.Customizer.withDefaults())
            .csrf(c->c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
            .exceptionHandling(e->e.authenticationEntryPoint((request,response,error)->response.setStatus(401)))
            .authorizeHttpRequests(a->a.anyRequest().authenticated()).build();
    }
    @Bean(name="integrationJobScheduler") ThreadPoolTaskScheduler integrationScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("timiroom-integration-");return scheduler;
    }
}
