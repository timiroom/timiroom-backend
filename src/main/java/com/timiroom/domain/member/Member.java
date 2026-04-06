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

    @Column(name = "member_name", nullable = false, unique = true)
    private String memberName;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "email", nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    private Role role;

    public static Member create(String memberName, String password, String email){
        Member member = new Member();
        member.memberName = memberName;
        member.password = password;
        member.email = email;
        member.role = Role.USER;
        return member;
    }
}
