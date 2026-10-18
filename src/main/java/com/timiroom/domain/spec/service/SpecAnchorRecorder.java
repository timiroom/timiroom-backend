package com.timiroom.domain.spec.service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.spec.dto.ProposalRef;
import com.timiroom.domain.spec.dto.SpecDocumentDto;
import com.timiroom.domain.spec.entity.SpecAnchor;
import com.timiroom.domain.spec.entity.SpecSnapshot;
import com.timiroom.domain.spec.repository.SpecAnchorRepository;
import com.timiroom.domain.spec.repository.SpecChangeProposalRepository;
import com.timiroom.domain.spec.repository.SpecSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

/**
 * 발행마다 명세 지문을 spec_anchor에 PENDING으로 남긴다. 체인 전송은 하지 않는다(워커 몫).
 * 직전 revision의 지문을 prevDigest로 이어 revision끼리 사슬이 되게 한다.
 * 기능 플래그와 상관없이 항상 기록한다 — 체인 전송을 켜면 쌓인 순서대로 보낸다.
 */
@Service @RequiredArgsConstructor
public class SpecAnchorRecorder {
    private final SpecAnchorRepository anchors;
    private final SpecSnapshotRepository snapshots;
    private final SpecChangeProposalRepository proposals;
    private final ObjectMapper mapper;

    /**
     * 지문이 없는 과거 스냅샷(기능 도입 전 발행분)을 revision 순서대로 채운다.
     * 사슬이 끊기지 않도록 발행 트랜잭션(프로젝트 잠금) 안에서, 새 스냅샷을 저장하기 전에 부른다.
     */
    @Transactional
    public void ensureProject(Long projectId) {
        int anchoredUpTo=anchors.findFirstByProjectIdOrderByRevisionDesc(projectId).map(SpecAnchor::getRevision).orElse(0);
        for(var snapshot:snapshots.findByProjectIdAndRevisionGreaterThanOrderByRevisionAsc(projectId,anchoredUpTo)) {
            var proposal=proposals.findByApprovedSnapshotId(snapshot.getSnapshotId())
                .map(p->new ProposalRef(p.getProposalId(),p.getProposalRevision(),p.getResultHash())).orElse(null);
            save(snapshot,documents(snapshot),proposal,true);
        }
    }

    @Transactional
    public SpecAnchor record(SpecSnapshot snapshot,List<SpecDocumentDto> documents,ProposalRef proposal) {
        return save(snapshot,documents,proposal,false);
    }

    private SpecAnchor save(SpecSnapshot s,List<SpecDocumentDto> documents,ProposalRef proposal,boolean backfilled) {
        String prev=s.getRevision()>1
            ? anchors.findByProjectIdAndRevision(s.getProjectId(),s.getRevision()-1).map(SpecAnchor::getDigest).orElse(null) : null;
        var built=SpecManifest.build(s.getProjectId(),s.getRevision(),s.getSnapshotId(),s.getPublishedAt(),documents,proposal,prev);
        return anchors.save(new SpecAnchor(s.getSnapshotId(),s.getProjectId(),s.getRevision(),built.json(),built.digest(),prev,backfilled,Instant.now()));
    }

    private List<SpecDocumentDto> documents(SpecSnapshot s) {
        try { return mapper.readValue(s.getDocumentsJson(),new TypeReference<List<SpecDocumentDto>>(){}); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Invalid stored snapshot",e); }
    }
}
