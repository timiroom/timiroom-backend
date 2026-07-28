package com.timiroom.domain.member;

import com.timiroom.domain.member.dto.MemberLoginRequest;
import com.timiroom.domain.member.dto.MemberRegisterRequest;
import com.timiroom.domain.member.service.MemberService;
import com.timiroom.storage.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class MemberController {

    private static final String GITHUB_LOGIN_PATTERN = "^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$";

    private final MemberService memberService;
    private final MemberRepository memberRepository;
    private final StorageService storageService;

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

    /** 계정 설정 수정 — PATCH /auth/me */
    @PatchMapping("/me")
    public ResponseEntity<?> updateMe(
            HttpSession session,
            @RequestBody Map<String, String> body
    ) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));
        }
        boolean updatesName = body.containsKey("name");
        boolean updatesGithubLogin = body.containsKey("githubLogin");
        if (!updatesName && !updatesGithubLogin) {
            return ResponseEntity.badRequest().body(Map.of("error", "수정할 계정 정보를 입력해주세요"));
        }

        String name = body.get("name");
        if (updatesName && (name == null || name.isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "이름을 입력해주세요"));
        }

        String githubLogin = body.get("githubLogin");
        String normalizedGithubLogin = githubLogin == null ? null : githubLogin.trim();
        if (updatesGithubLogin && normalizedGithubLogin != null && !normalizedGithubLogin.isBlank()
                && !normalizedGithubLogin.matches(GITHUB_LOGIN_PATTERN)) {
            return ResponseEntity.badRequest().body(Map.of("error", "올바른 GitHub 사용자명을 입력해주세요"));
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new RuntimeException("Member not found"));
        if (updatesName) member.updateName(name.trim());
        if (updatesGithubLogin) {
            member.updateGithubLogin(normalizedGithubLogin == null || normalizedGithubLogin.isBlank()
                    ? null : normalizedGithubLogin);
        }
        memberRepository.save(member);
        return ResponseEntity.ok(memberToMap(member));
    }

    /** 프로필 이미지 업로드 — POST /auth/me/avatar */
    @PostMapping("/me/avatar")
    public ResponseEntity<?> uploadAvatar(
            HttpSession session,
            @RequestParam("file") MultipartFile file
    ) {
        Long memberId = (Long) session.getAttribute("memberId");
        if (memberId == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));

        try {
            Member member = memberRepository.findById(memberId)
                    .orElseThrow(() -> new RuntimeException("Member not found"));
            String oldUrl = member.getProfileImageUrl();
            String url = storageService.uploadProfileImage(file, "profile-images");
            member.updateProfileImageUrl(url);
            memberRepository.save(member);
            if (oldUrl != null) storageService.delete(oldUrl);
            return ResponseEntity.ok(memberToMap(member));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "프로필 이미지 업로드 실패: " + e.getMessage()));
        }
    }

    private Map<String, Object> memberToMap(Member member) {
        java.util.HashMap<String, Object> map = new java.util.HashMap<>();
        map.put("id", member.getMemberId());
        map.put("name", member.getDisplayName());
        map.put("email", member.getEmail());
        map.put("provider", member.getProvider());
        map.put("avatarUrl", member.getProfileImageUrl());
        map.put("githubLogin", member.getGithubLogin());
        return map;
    }
}
