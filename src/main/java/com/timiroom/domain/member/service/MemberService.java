package com.timiroom.domain.member.service;

import com.timiroom.domain.member.Member;
import com.timiroom.domain.member.MemberRepository;
import com.timiroom.domain.member.dto.MemberLoginRequest;
import com.timiroom.domain.member.dto.MemberRegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    public void register(MemberRegisterRequest request){

        var hash = passwordEncoder.encode(request.getPassword());

        Member member = Member.create(request.getMemberName(), hash, request.getEmail());

        memberRepository.save(member);
    }

    public void login(MemberLoginRequest request, HttpServletRequest httpRequest) {

        var token = new UsernamePasswordAuthenticationToken(
                request.getMemberName(),
                request.getPassword()
        );

        authenticationManager.authenticate(token); // 인증 실패 시 예외 발생

        Member member = memberRepository.findAllByMemberName(request.getMemberName())
                .orElseThrow(() -> new RuntimeException("회원 없음"));

        // 세션에 memberId만 저장
        HttpSession session = httpRequest.getSession(true);
        session.setAttribute("memberId", member.getMemberId());
    }
}
