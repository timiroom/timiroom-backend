package com.timiroom.domain.spec;

import com.timiroom.domain.spec.entity.SpecAnchor;
import com.timiroom.domain.spec.entity.SpecSnapshot;
import com.timiroom.domain.spec.repository.SpecAnchorRepository;
import com.timiroom.domain.spec.repository.SpecSnapshotRepository;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 지문·스냅샷 리포지토리를 메모리 맵으로 흉내 낸다. 한 프로젝트(1번)만 다룬다. */
final class AnchorTestStore {
    final SpecAnchorRepository anchors=mock(SpecAnchorRepository.class);
    final SpecSnapshotRepository snapshots=mock(SpecSnapshotRepository.class);
    final TreeMap<Integer,SpecAnchor> byRevision=new TreeMap<>();
    final TreeMap<Integer,SpecSnapshot> snapshotByRevision=new TreeMap<>();

    AnchorTestStore() {
        lenient().when(anchors.save(any())).thenAnswer(i->{SpecAnchor a=i.getArgument(0);byRevision.put(a.getRevision(),a);return a;});
        lenient().when(anchors.findByProjectIdAndRevision(eq(1L),anyInt())).thenAnswer(i->Optional.ofNullable(byRevision.get((int)i.getArgument(1))));
        lenient().when(anchors.findFirstByProjectIdOrderByRevisionDesc(1L)).thenAnswer(i->byRevision.isEmpty()?Optional.empty():Optional.of(byRevision.lastEntry().getValue()));
        lenient().when(anchors.findBySnapshotId(any())).thenAnswer(i->byRevision.values().stream().filter(a->a.getSnapshotId().equals(i.getArgument(0))).findFirst());
        lenient().when(snapshots.findByProjectIdAndRevisionGreaterThanOrderByRevisionAsc(eq(1L),anyInt()))
            .thenAnswer(i->new ArrayList<>(snapshotByRevision.tailMap((int)i.getArgument(1),false).values()));
        lenient().when(snapshots.findBySnapshotIdAndProjectId(any(),eq(1L)))
            .thenAnswer(i->snapshotByRevision.values().stream().filter(s->s.getSnapshotId().equals(i.getArgument(0))).findFirst());
    }
    SpecSnapshot snapshot(int revision,String documentsJson) {
        var s=new SpecSnapshot(1L,revision,2L,documentsJson);
        snapshotByRevision.put(revision,s);
        return s;
    }
}
