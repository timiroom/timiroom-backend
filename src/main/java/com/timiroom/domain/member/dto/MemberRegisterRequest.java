package com.timiroom.domain.member.dto;

import lombok.Getter;

@Getter
public class MemberRegisterRequest {
    private String memberName;
    private String password;
    private String email;
}
