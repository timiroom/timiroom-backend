package com.timiroom.domain.member;

import com.timiroom.domain.member.dto.MemberLoginRequest;
import com.timiroom.domain.member.dto.MemberRegisterRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class MemberController {

    private final MemberService memberService;

    @PostMapping("/register")
    public void register(@RequestBody MemberRegisterRequest request){
        memberService.register(request);
    }

    @PostMapping("/login")
    public void sessionLogin(@RequestBody MemberLoginRequest request, HttpSession session){
        memberService.login(request, session);
    }
}
