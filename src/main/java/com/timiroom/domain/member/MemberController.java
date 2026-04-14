package com.timiroom.domain.member;

import com.timiroom.domain.member.dto.MemberLoginRequest;
import com.timiroom.domain.member.dto.MemberRegisterRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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
        System.out.println("hello");
        memberService.login(request, session);
    }


    @GetMapping("/home")
    @ResponseBody
    public Map<String, Object> home(@AuthenticationPrincipal OAuth2User user) {
        System.out.println(user.getAttributes());
        return user.getAttributes();
    }

}


