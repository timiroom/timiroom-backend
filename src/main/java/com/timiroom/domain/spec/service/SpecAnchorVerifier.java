package com.timiroom.domain.spec.service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.spec.dto.AnchorVerification;
import com.timiroom.domain.spec.dto.AnchorVerification.Status;
import com.timiroom.domain.spec.dto.SpecAnchorDto;
import com.timiroom.domain.spec.dto.SpecDocumentDto;
import com.timiroom.domain.spec.entity.SpecAnchor;
import com.timiroom.domain.spec.entity.SpecSnapshot;
import com.timiroom.domain.spec.repository.SpecAnchorRepository;
import com.timiroom.domain.spec.repository.SpecSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * 스냅샷의 지문 상태 조회, 매니페스트 원문 내려받기, 검증.
 * 체인 전송 전이라 CHAIN 단계는 SKIPPED다. DOCUMENTS·MANIFEST만으로 "발행 뒤 DB가 바뀌었는지"를 확인한다.
 */
@Service @RequiredArgsConstructor
public class SpecAnchorVerifier {
    private static final boolean CHAIN_ENABLED=false;
    private final SpecAnchorRepository anchors;
    private final SpecSnapshotRepository snapshots;
    private final DocumentAccessService access;
    private final ObjectMapper mapper;

    public record Manifest(int revision,String json) {}

    @Transactional(readOnly=true)
    public SpecAnchorDto anchor(Long projectId,Long actorId,UUID snapshotId) {
        var snapshot=snapshot(projectId,actorId,snapshotId);
        return anchors.findBySnapshotId(snapshotId).map(a->SpecAnchorDto.of(a,CHAIN_ENABLED))
            .orElseGet(()->SpecAnchorDto.none(projectId,snapshot.getRevision(),CHAIN_ENABLED));
    }

    @Transactional(readOnly=true)
    public Manifest manifest(Long projectId,Long actorId,UUID snapshotId) {
        snapshot(projectId,actorId,snapshotId);
        var anchor=anchors.findBySnapshotId(snapshotId).orElseThrow(()->new IllegalArgumentException("ANCHOR_NOT_FOUND"));
        return new Manifest(anchor.getRevision(),anchor.getManifestJson());
    }

    @Transactional(readOnly=true)
    public AnchorVerification verify(Long projectId,Long actorId,UUID snapshotId) {
        var snapshot=snapshot(projectId,actorId,snapshotId);
        var anchor=anchors.findBySnapshotId(snapshotId);
        if(anchor.isEmpty()) return AnchorVerification.of(Status.SKIPPED,Status.SKIPPED,Status.SKIPPED);
        return AnchorVerification.of(documents(snapshot,anchor.get()),manifest(anchor.get()),Status.SKIPPED);
    }

    /** 스냅샷 본문을 다시 해시해 매니페스트를 새로 만들고, 저장된 원문과 글자 그대로 같은지 본다. */
    private Status documents(SpecSnapshot snapshot,SpecAnchor anchor) {
        try {
            var stored=SpecManifest.parse(anchor.getManifestJson());
            if(stored.path("v").asInt()!=SpecManifest.VERSION) return Status.FAIL;
            var rehashed=readDocuments(snapshot).stream()
                .map(d->new SpecDocumentDto(d.type(),d.artifactId(),d.executionId(),d.version(),DocumentHash.of(d.content()),d.content())).toList();
            // proposal·prevDigest는 매니페스트 값을 그대로 쓴다 — 그 둘의 위·변조는 MANIFEST 단계(지문 대조)가 잡는다.
            String prev=stored.path("prevDigest").isNull()?null:stored.path("prevDigest").asText();
            var expected=SpecManifest.build(snapshot.getProjectId(),snapshot.getRevision(),snapshot.getSnapshotId(),snapshot.getPublishedAt(),
                rehashed,SpecManifest.proposal(stored),prev);
            return expected.json().equals(anchor.getManifestJson()) ? Status.PASS : Status.FAIL;
        } catch(RuntimeException e) { return Status.FAIL; }
    }

    /** 매니페스트 원문의 지문이 저장된 지문과 같고, 직전 revision 지문과 사슬로 이어지는지 본다. */
    private Status manifest(SpecAnchor anchor) {
        try {
            if(!SpecManifest.digest(anchor.getManifestJson()).equals(anchor.getDigest())) return Status.FAIL;
            var stored=SpecManifest.parse(anchor.getManifestJson());
            String prevInManifest=stored.path("prevDigest").isNull()?null:stored.path("prevDigest").asText();
            if(!Objects.equals(prevInManifest,anchor.getPrevDigest())) return Status.FAIL;
            String expectedPrev=anchor.getRevision()>1
                ? anchors.findByProjectIdAndRevision(anchor.getProjectId(),anchor.getRevision()-1).map(SpecAnchor::getDigest).orElse(null) : null;
            return Objects.equals(expectedPrev,anchor.getPrevDigest()) ? Status.PASS : Status.FAIL;
        } catch(RuntimeException e) { return Status.FAIL; }
    }

    private SpecSnapshot snapshot(Long projectId,Long actorId,UUID snapshotId) {
        access.requireRead(projectId,actorId);
        return snapshots.findBySnapshotIdAndProjectId(snapshotId,projectId).orElseThrow(()->new IllegalArgumentException("SNAPSHOT_NOT_FOUND"));
    }
    private List<SpecDocumentDto> readDocuments(SpecSnapshot s) {
        try { return mapper.readValue(s.getDocumentsJson(),new TypeReference<List<SpecDocumentDto>>(){}); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Invalid stored snapshot",e); }
    }
}
