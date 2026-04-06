package com.timiroom.domain.member.dto;

import lombok.Getter;

@Getter
public class MemberLoginRequest {
    private String memberName;
    private String password;
}
