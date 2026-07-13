package com.timiroom.domain.member.controller;

import com.timiroom.domain.member.entity.Member;
import com.timiroom.domain.member.exception.code.MemberSuccessCode;
import com.timiroom.domain.member.repository.MemberRepository;
import com.timiroom.domain.member.service.MemberService;
import com.timiroom.global.ApiResponse;
import com.timiroom.global.apiPayload.code.BaseSuccessCode;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class MemberController {

    private final MemberService memberService;
    private final MemberRepository memberRepository;


    @GetMapping("/me")
    public ApiResponse<Member> getMe(HttpSession session){
        BaseSuccessCode code = MemberSuccessCode.OK;
        return ApiResponse.onSuccess(code, memberService.getInfo(session));
    }

}
