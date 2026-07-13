package com.timiroom.domain.member.dto;

import lombok.Getter;

public class ProjectReqDTO {

    @Getter
    public class Create{
        private Long teamId;
        private String projectName;
        private String description;
    }
}
