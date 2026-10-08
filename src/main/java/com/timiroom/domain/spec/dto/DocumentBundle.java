package com.timiroom.domain.spec.dto;
import java.util.*;
public record DocumentBundle(Long projectId,UUID snapshotId,List<SpecDocumentDto> documents) {
    public DocumentBundle { documents=List.copyOf(documents); }
    public static DocumentBundle from(SpecSnapshotDto s){return new DocumentBundle(s.projectId(),s.snapshotId(),s.documents());}
}
