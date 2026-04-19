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

    @Column(name = "password")
    private String password;  // 소셜 로그인은 null 가능

    @Column(name = "email", nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Column(name = "provider")  // "local", "github", "google" 등
    private String provider;

    @Column(name = "provider_id")  // 소셜 로그인 고유 ID
    private String providerId;

    // 로컬 로그인용
    public static Member create(String memberName, String password, String email) {
        Member member = new Member();
        member.memberName = memberName;
        member.password = password;
        member.email = email;
        member.role = Role.USER;
        member.provider = "local";
        return member;
    }

    // 소셜 로그인용
    public static Member createOAuth(String memberName, String email, String provider, String providerId) {
        Member member = new Member();
        member.memberName = memberName;
        member.email = email;
        member.role = Role.USER;
        member.provider = provider;
        member.providerId = providerId;
        return member;
    }
}