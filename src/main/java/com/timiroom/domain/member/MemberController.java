package com.timiroom.domain.member;

import com.timiroom.domain.member.dto.MemberLoginRequest;
import com.timiroom.domain.member.dto.MemberRegisterRequest;
import com.timiroom.domain.member.service.MemberService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class MemberController {

    private final MemberService memberService;
    private final MemberRepository memberRepository;

    @PostMapping("/register")
    public void register(@RequestBody MemberRegisterRequest request) {
        memberService.register(request);
    }

    @PostMapping("/login")
    public void login(@RequestBody MemberLoginRequest request, HttpServletRequest httpRequest) {
        memberService.login(request, httpRequest);
    }
}
