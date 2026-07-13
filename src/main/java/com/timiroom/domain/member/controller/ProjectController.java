package com.timiroom.domain.member.controller;

import com.timiroom.domain.member.dto.ProjectMemberReqDTO;
import com.timiroom.domain.member.dto.ProjectReqDTO;
import com.timiroom.domain.member.entity.Project;
import com.timiroom.domain.member.entity.mapping.ProjectMember;
import com.timiroom.domain.member.exception.code.MemberSuccessCode;
import com.timiroom.domain.member.service.ProjectService;
import com.timiroom.global.ApiResponse;
import com.timiroom.global.apiPayload.code.BaseSuccessCode;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    /** 프로젝트 생성 */
    @PostMapping
    public ApiResponse<Project> create(HttpSession session,
                                          @RequestBody ProjectReqDTO.Create request) {
        BaseSuccessCode code = MemberSuccessCode.OK;
        return ApiResponse.onSuccess(code ,projectService.create(session, request));
    }

    /** 내 프로젝트 목록 */
    @GetMapping
    public ApiResponse<List<Project>> myProjects(HttpSession session) {
        BaseSuccessCode code = MemberSuccessCode.OK;
        return ApiResponse.onSuccess(code ,projectService.getMyProjects(session));
    }

    /** 팀 내 프로젝트 목록 */
    @GetMapping("/team/{teamId}")
    public ApiResponse<List<Project>> byTeam(@PathVariable Long teamId) {
        BaseSuccessCode code = MemberSuccessCode.OK;
        return ApiResponse.onSuccess(code ,projectService.getByTeam(teamId));
    }

    /** 프로젝트 단건 조회 */
    @GetMapping("/{projectId}")
    public ApiResponse<Project> getOne(@PathVariable Long projectId) {
        BaseSuccessCode code = MemberSuccessCode.OK;
        return ApiResponse.onSuccess(code ,projectService.getById(projectId));
    }

    /** 프로젝트 멤버 목록 */
    @GetMapping("/{projectId}/members")
    public ApiResponse<List<ProjectMember>> members(@PathVariable Long projectId) {
        BaseSuccessCode code = MemberSuccessCode.OK;
        return ApiResponse.onSuccess(code ,projectService.getMembers(projectId));
    }

    /** 프로젝트 멤버 추가 */
    @PostMapping("/{projectId}/members")
    public ApiResponse<ProjectMember> addMember(@PathVariable Long projectId,
                                                   @RequestBody ProjectMemberReqDTO.Add request) {
        BaseSuccessCode code = MemberSuccessCode.OK;
        return ApiResponse.onSuccess(code ,projectService.addMember(projectId, request));
    }

    /** 프로젝트 삭제 */
    @DeleteMapping("/{projectId}")
    public ApiResponse<Void> delete(HttpSession session,
                                       @PathVariable Long projectId) {
        projectService.delete(session, projectId);
        BaseSuccessCode code = MemberSuccessCode.OK;
        return ApiResponse.onSuccess(code ,null);
    }
}