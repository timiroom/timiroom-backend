package com.timiroom.domain.member.dto;

import com.timiroom.domain.member.enums.ProjectRole;
import com.timiroom.domain.member.enums.Role;
import lombok.Getter;

public class ProjectMemberReqDTO {

    @Getter
    public class Add{
        private Long memberId;
        private ProjectRole role;
    }
}
