package com.timiroom.domain.oauth;

import com.timiroom.domain.member.entity.Member;
import com.timiroom.domain.member.repository.MemberRepository;
import com.timiroom.domain.member.enums.Provider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final MemberRepository memberRepository;

    @Value("${frontend.url}")
    private String frontendUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();

        String provider = ((OAuth2AuthenticationToken) authentication).getAuthorizedClientRegistrationId();

        // ✅ provider에 따라 분기 처리
        String providerId;
        if (provider.equals("google")) {
            providerId = oAuth2User.getAttribute("sub").toString(); // Google은 sub
        } else {
            providerId = oAuth2User.getAttribute("id").toString();  // GitHub는 id
        }

        Provider providerEnum = Provider.valueOf(provider.toUpperCase());
        Member member = memberRepository.findByProviderAndProviderId(providerEnum, providerId)
                .orElseThrow(() -> new RuntimeException("회원 없음"));

        HttpSession session = request.getSession(true);
        session.setAttribute("memberId", member.getMemberId());

        clearAuthenticationAttributes(request);
        response.sendRedirect(frontendUrl + "/auth/callback");
    }
}