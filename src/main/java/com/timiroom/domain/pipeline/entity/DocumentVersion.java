package com.timiroom.domain.pipeline.entity;

import com.timiroom.domain.member.entity.Member;
import com.timiroom.global.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CollectionIdMutability;

@Entity
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "document_version")
public class DocumentVersion extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "artifact_id")
    private PipelineArtifact artifact;

    @Lob
    @Column(name = "content")
    private String content;

    @Column(name = "change_summary")
    private String change_summary;
}
