package com.timiroom.domain.oauth;

import com.timiroom.domain.member.Member;
import com.timiroom.domain.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OAuth2MemberService extends DefaultOAuth2UserService {

    private final MemberRepository memberRepository;



    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {

        OAuth2User oAuth2User = super.loadUser(userRequest);

        // 어떤 소셜인지 → "github"
        String provider = userRequest.getClientRegistration().getRegistrationId();

        String providerId;
        if (provider.equals("google")) {
            providerId = oAuth2User.getAttribute("sub").toString(); // Google은 sub
        } else {
            providerId = oAuth2User.getAttribute("id").toString();  // GitHub는 id
        }

        String memberName = provider + "_" + providerId;

        // 이메일 없는 GitHub 계정도 있어서 없으면 임시 이메일 생성
        String email = oAuth2User.getAttribute("email") != null
                ? oAuth2User.getAttribute("email")
                : provider + "_" + providerId + "@timiroom.com";

        memberRepository.findByProviderAndProviderId(provider, providerId)
                .orElseGet(() -> memberRepository.save(
                        Member.createOAuth(memberName, email, provider, providerId)
                ));

        return oAuth2User;
    }

}
