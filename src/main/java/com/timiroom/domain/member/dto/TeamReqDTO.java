package com.timiroom.domain.member.dto;

import lombok.Getter;

public class TeamReqDTO {

    @Getter
    public class Create{
        private String teamName;
        private String description;
    }
}
