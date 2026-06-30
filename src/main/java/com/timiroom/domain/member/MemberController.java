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
        return ResponseEntity.ok(memberToMap(member));
    }

    /** 이름 수정 — PATCH /auth/me */
    @PatchMapping("/me")
    public ResponseEntity<?> updateMe(
            HttpSession session,
            @RequestBody Map<String, String> body
    ) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
        }
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "이름을 입력해주세요"));
        }
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new RuntimeException("Member not found"));
        member.updateName(name);
        memberRepository.save(member);
        return ResponseEntity.ok(memberToMap(member));
    }

    private Map<String, Object> memberToMap(Member member) {
        return Map.of(
                "id", member.getMemberId(),
                "name", member.getMemberName(),
                "email", member.getEmail(),
                "provider", member.getProvider()
        );
    }
}
