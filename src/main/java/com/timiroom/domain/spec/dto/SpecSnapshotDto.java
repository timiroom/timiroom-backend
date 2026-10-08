package com.timiroom.domain.spec.dto;
import java.time.Instant;
import java.util.*;
public record SpecSnapshotDto(UUID snapshotId,Long projectId,int revision,Long publishedBy,Instant publishedAt,List<SpecDocumentDto> documents) {
    public SpecSnapshotDto { documents=List.copyOf(documents); }
}
