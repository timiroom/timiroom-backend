package com.timiroom.domain.member.entity;

import static jakarta.persistence.FetchType.LAZY;

import com.timiroom.domain.member.enums.ProjectStatus;
import com.timiroom.global.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "project")
public class Project extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @Column(name = "project_name")
    private String teamName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "invite_code")
    private String inviteCode;

    @Enumerated(EnumType.STRING)
    private ProjectStatus staus;
}
