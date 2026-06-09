package com.timiroom.domain.commit;

import com.timiroom.global.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "commits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Commit extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "commit_id")
    private Long commitId;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Builder
    public Commit(Long projectId, Long memberId, String message) {
        this.projectId = projectId;
        this.memberId = memberId;
        this.message = message;
    }
}
