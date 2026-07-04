package com.timiroom.domain.oauth;

import com.timiroom.domain.member.Member;
import com.timiroom.domain.member.MemberRepository;
import com.timiroom.domain.member.Provider;
import com.timiroom.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URI;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuth2MemberService extends DefaultOAuth2UserService {

    private final MemberRepository memberRepository;
    private final StorageService storageService;



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

        // 소셜 provider의 실제 표시 이름 (Google: name, GitHub: name or login)
        String displayName = oAuth2User.getAttribute("name");
        if (displayName == null || displayName.isBlank()) {
            displayName = oAuth2User.getAttribute("login"); // GitHub fallback
        }
        final String resolvedDisplayName = displayName;

        // 프로필 사진 URL (Google: picture, GitHub: avatar_url)
        String avatarUrl = provider.equals("google")
                ? oAuth2User.getAttribute("picture")
                : oAuth2User.getAttribute("avatar_url");

        Provider providerEnum = Provider.valueOf(provider.toUpperCase());

        Member member = memberRepository.findByProviderAndProviderId(providerEnum, providerId)
                .orElseGet(() -> memberRepository.save(
                        Member.createOAuth(memberName, email, providerEnum, providerId)
                ));

        boolean dirty = false;

        // 로그인마다 nickname 동기화 (기존 유저도 자동 갱신)
        if (resolvedDisplayName != null && !resolvedDisplayName.equals(member.getNickname())) {
            member.updateNickname(resolvedDisplayName);
            dirty = true;
        }

        // 프로필 사진이 없을 때만 OAuth 사진을 MinIO에 저장 (직접 업로드한 사진은 유지)
        if (avatarUrl != null && member.getProfileImageUrl() == null) {
            String minioUrl = downloadAndUpload(avatarUrl, "profile-images/" + provider);
            if (minioUrl != null) {
                member.updateProfileImageUrl(minioUrl);
                dirty = true;
            }
        }

        if (dirty) memberRepository.save(member);

        return oAuth2User;
    }

    private String downloadAndUpload(String avatarUrl, String folder) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(avatarUrl).toURL().openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            conn.connect();

            byte[] bytes = conn.getInputStream().readAllBytes();
            conn.disconnect();

            return storageService.uploadProfileImage(bytes, folder);
        } catch (Exception e) {
            log.warn("OAuth 프로필 사진 MinIO 업로드 실패 ({}): {}", avatarUrl, e.getMessage());
            return null;
        }
    }
}
