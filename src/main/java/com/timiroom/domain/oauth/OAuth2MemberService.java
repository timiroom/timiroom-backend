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

        // 6번에서 가져온 GitHub 유저 정보
        // {id: 202200608, login: "username", email: "abc@gmail.com", ...}
        OAuth2User oAuth2User = super.loadUser(userRequest);

        // 어떤 소셜인지 → "github"
        String provider = userRequest.getClientRegistration().getRegistrationId();

        // GitHub 고유 ID → "202200608"
        String providerId = oAuth2User.getAttribute("id").toString();

        String memberName = provider + "_" + providerId;  // ← 추가

        // 이메일 없는 GitHub 계정도 있어서 없으면 임시 이메일 생성
        String email = oAuth2User.getAttribute("email") != null
                ? oAuth2User.getAttribute("email")
                : provider + "_" + providerId + "@timiroom.com";

        // DB에서 찾고 없으면 자동 회원가입
        memberRepository.findByProviderAndProviderId(provider, providerId)
                .orElseGet(() -> memberRepository.save(
                        Member.createOAuth(memberName, email, provider, providerId)
                ));

        // GitHub에서 받은 유저 정보 그대로 반환
        // → 이후 OAuth2SuccessHandler로 넘어감

        System.out.println("OAuth2 attributes: " + oAuth2User.getAttributes());

        return oAuth2User;
    }

}
