package com.timiroom.domain.member.entity;

import com.timiroom.domain.member.enums.Role;
import com.timiroom.global.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Table(name = "member")
public class Member extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_name", unique = true)
    private String memberName;

    @Column(name = "password")
    private String password;

    @Column(name = "email", nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Column(name = "provider")  // "local", "github", "google" 등
    private String provider;

    @Column(name = "provider_id")  // 소셜 로그인 고유 ID
    private String providerId;

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
