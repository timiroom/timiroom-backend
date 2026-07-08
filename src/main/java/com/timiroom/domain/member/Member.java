package com.timiroom.domain.member;

import com.timiroom.global.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
public class Member extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "member_name", unique = true)
    private String memberName;

    @Column(name = "password", nullable = true)
    private String password;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "nickname", length = 100)
    private String nickname;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 20)
    private Provider provider;

    @Column(name = "provider_id")
    private String providerId;

    // 로컬 로그인용
    public static Member create(String memberName, String password, String email) {
        Member member = new Member();
        member.memberName = memberName;
        member.password = password;
        member.email = email;
        member.role = Role.USER;
        member.provider = Provider.LOCAL;
        return member;
    }

    public void updateName(String memberName) {
        this.memberName = memberName;
    }

    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    public void updateProfileImageUrl(String profileImageUrl) {
        this.profileImageUrl = profileImageUrl;
    }

    public String getDisplayName() {
        return nickname != null && !nickname.isBlank() ? nickname : memberName;
    }

    // 소셜 로그인용
    public static Member createOAuth(String memberName, String email, Provider provider, String providerId) {
        Member member = new Member();
        member.memberName = memberName;
        member.email = email;
        member.password = "";
        member.role = Role.USER;
        member.provider = provider;
        member.providerId = providerId;
        return member;
    }
}
