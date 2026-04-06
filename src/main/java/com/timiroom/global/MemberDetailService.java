package com.timiroom.global;

import com.timiroom.domain.member.Member;
import com.timiroom.domain.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MemberDetailService implements UserDetailsService {

    private final MemberRepository memberRepository;

    @Override
    public UserDetails loadUserByUsername(String memberName) throws UsernameNotFoundException{
        Member member = memberRepository.findAllByMemberName(memberName)
                .orElseThrow(() -> new UsernameNotFoundException("이름을 찾을 수 없습니다."));

        return User.builder()
                .username(member.getMemberName())
                .password(member.getPassword())
                .roles(member.getRole().name())
                .build();
    }

}
