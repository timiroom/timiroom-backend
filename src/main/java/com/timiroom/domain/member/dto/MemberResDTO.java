package com.timiroom.domain.member.dto;

import com.timiroom.domain.member.enums.Provider;
import lombok.AllArgsConstructor;
import lombok.Getter;

public class MemberResDTO {

    @Getter
    @AllArgsConstructor
    public static class Detail {
        private Long memberId;
        private String memberName;
        private String email;
        private Provider provider;
    }
}
