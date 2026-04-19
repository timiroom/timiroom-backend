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

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) {
            return ResponseEntity.status(401).build();
        }
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new RuntimeException("회원 없음"));

        return ResponseEntity.ok(Map.of(
                "memberId", member.getMemberId(),
                "memberName", member.getMemberName(),
                "email", member.getEmail(),
                "provider", member.getProvider()
        ));
    }
}
