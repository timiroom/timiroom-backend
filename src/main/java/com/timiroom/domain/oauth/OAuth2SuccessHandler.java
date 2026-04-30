package com.timiroom.domain.oauth;

import com.timiroom.domain.member.Member;
import com.timiroom.domain.member.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
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

        Member member = memberRepository.findByProviderAndProviderId(provider, providerId)
                .orElseThrow(() -> new RuntimeException("회원 없음"));

        HttpSession session = request.getSession(true);
        session.setAttribute("memberId", member.getMemberId());

        response.sendRedirect("/auth/me");
    }
}