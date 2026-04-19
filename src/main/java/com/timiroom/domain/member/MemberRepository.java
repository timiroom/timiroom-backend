package com.timiroom.domain.member;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findAllByMemberName(String memberName);
    Optional<Member> findByProviderAndProviderId(String provider, String providerId);
}
