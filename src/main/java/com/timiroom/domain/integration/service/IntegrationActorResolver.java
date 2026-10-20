package com.timiroom.domain.integration.service;
import com.timiroom.domain.member.repository.MemberRepository;
import com.timiroom.domain.member.enums.Provider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.*;

@Service @RequiredArgsConstructor
public class IntegrationActorResolver {
    private final MemberRepository members;
    public Long memberId(Authentication authentication) {
        if(!(authentication instanceof OAuth2AuthenticationToken oauth) || !authentication.isAuthenticated())
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.ACCESS_DENIED);
        Provider provider;
        try { provider=Provider.valueOf(oauth.getAuthorizedClientRegistrationId().toUpperCase(java.util.Locale.ROOT)); }
        catch(IllegalArgumentException e) {throw new OAuth2AuthenticationException(OAuth2ErrorCodes.ACCESS_DENIED);}
        Object id=oauth.getPrincipal().getAttribute(provider==Provider.GOOGLE?"sub":"id");
        if(id==null) throw new OAuth2AuthenticationException(OAuth2ErrorCodes.ACCESS_DENIED);
        return members.findByProviderAndProviderId(provider,id.toString()).orElseThrow(()->new OAuth2AuthenticationException(OAuth2ErrorCodes.ACCESS_DENIED)).getMemberId();
    }
}
