package com.timiroom.domain.member.service;

import com.timiroom.domain.member.entity.Member;
import com.timiroom.domain.member.exception.MemberException;
import com.timiroom.domain.member.exception.code.MemberErrorCode;
import com.timiroom.domain.member.repository.MemberRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    public Member getInfo(HttpSession session){
        Long memberId = (Long) session.getAttribute("memberID");
        if(memberId == null){
            throw new MemberException(MemberErrorCode.UNAUTHORIZED);
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(()-> new MemberException(MemberErrorCode.NOT_FOUND));

        return Member.builder()
                .memberId(memberId)
                .memberName(member.getMemberName())
                .email(member.getEmail())
                .provider(member.getProvider())
                .build();
    }
}
