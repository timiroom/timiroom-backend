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

    @GetMapping("/me")
    public ResponseEntity<?> getMe(HttpSession session) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new RuntimeException("Member not found"));

        return ResponseEntity.ok(Map.of(
                "id", member.getMemberId(),
                "name", member.getMemberName(),
                "email", member.getEmail(),
                "provider", member.getProvider()
        ));
    }
}
