package com.timiroom.domain.member;

import com.timiroom.domain.member.dto.MemberLoginRequest;
import com.timiroom.domain.member.dto.MemberRegisterRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

    public void login(MemberLoginRequest request, HttpSession session){

        var token = new UsernamePasswordAuthenticationToken(request.getMemberName(), request.getPassword());

        Authentication authentication = authenticationManager.authenticate(token);

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
