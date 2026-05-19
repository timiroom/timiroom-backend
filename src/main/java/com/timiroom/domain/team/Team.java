package com.timiroom.domain.team;

import com.timiroom.global.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "team")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Team extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "team_name", length = 100, nullable = false)
    private String teamName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "invite_code", length = 20, unique = true)
    private String inviteCode;
}
