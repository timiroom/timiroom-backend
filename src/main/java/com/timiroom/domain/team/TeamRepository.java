package com.timiroom.domain.team;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {
    Optional<Team> findByInviteCode(String inviteCode);
    boolean existsByInviteCode(String inviteCode);
}
