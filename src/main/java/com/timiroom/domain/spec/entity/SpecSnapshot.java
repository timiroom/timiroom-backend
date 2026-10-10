package com.timiroom.domain.spec.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="spec_snapshot",uniqueConstraints=@UniqueConstraint(columnNames={"project_id","revision"}))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class SpecSnapshot {
    @Id @Column(name="snapshot_id",nullable=false) private UUID snapshotId;
    @Column(name="project_id",nullable=false) private Long projectId;
    @Column(nullable=false) private int revision;
    @Column(name="published_by",nullable=false) private Long publishedBy;
    @Column(name="published_at",nullable=false) private Instant publishedAt;
    @Column(name="documents_json",nullable=false,columnDefinition="TEXT") private String documentsJson;
    public SpecSnapshot(Long projectId,int revision,Long actorId,String documentsJson) {
        this.snapshotId=UUID.randomUUID();this.projectId=projectId;this.revision=revision;
        this.publishedBy=actorId;this.publishedAt=Instant.now();this.documentsJson=documentsJson;
    }
}
