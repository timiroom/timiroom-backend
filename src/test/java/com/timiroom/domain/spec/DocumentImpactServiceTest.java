package com.timiroom.domain.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.graph.service.GraphCalculator;
import com.timiroom.domain.spec.dto.*;
import com.timiroom.domain.spec.service.*;
import com.timiroom.infra.ragpipeline.DocumentEditClient;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentImpactServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final DocumentEditClient client = mock(DocumentEditClient.class);
    final DocumentImpactService service = new DocumentImpactService(new GraphCalculator(mapper), client, mapper);
    final UUID snapshot = UUID.randomUUID();
    DocumentBundle bundle(String api) {
        return new DocumentBundle(1L, snapshot, List.of(
            new SpecDocumentDto(API_SPEC, 10L, 5L, 2, "h", api),
            new SpecDocumentDto(DB_SCHEMA, 11L, 5L, 3, "h2", "{\"tables\":[{\"name\":\"users\",\"columns\":[{\"name\":\"id\"}]}]}")
        ));
    }
    @Test void noChangesSkipsModelAndRecordsSnapshot() {
        var before = bundle("{\"endpoints\":[]}");
        var result = service.analyze(before, before, Set.of(DB_SCHEMA));
        assertThat(result.updates()).isEmpty();
        assertThat(result.snapshotId()).isEqualTo(snapshot);
        verifyNoInteractions(client);
    }
    @Test void wordingOnlyCanHaveNoSemanticUpdates() throws Exception {
        when(client.analyzeImpact(anyString(), any())).thenReturn(mapper.readTree("{\"content\":\"{\\\"summary\\\":\\\"표현 변경\\\",\\\"updates\\\":[]}\"}"));
        var result = service.analyze(bundle("{\"endpoints\":[]}"), bundle("{\"endpoints\":[],\"note\":\"설명\"}"), Set.of(DB_SCHEMA));
        assertThat(result.updates()).isEmpty();
        assertThat(result.summary()).isEqualTo("표현 변경");
    }
    @Test void rejectsSourceOrOutsideTargetChanges() throws Exception {
        when(client.analyzeImpact(anyString(), any())).thenReturn(mapper.readTree("{\"content\":\"{\\\"updates\\\":[{\\\"type\\\":\\\"PRD\\\",\\\"document\\\":{}}]}\"}"));
        assertThatThrownBy(() -> service.analyze(bundle("{\"endpoints\":[]}"), bundle("{\"endpoints\":[],\"note\":\"new\"}"), Set.of(DB_SCHEMA)))
            .isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsDeletedUnrelatedTable() throws Exception {
        when(client.analyzeImpact(anyString(), any())).thenReturn(mapper.readTree("{\"content\":\"{\\\"updates\\\":[{\\\"type\\\":\\\"DB_SCHEMA\\\",\\\"document\\\":{\\\"tables\\\":[]}}]}\"}"));
        assertThatThrownBy(() -> service.analyze(bundle("{\"endpoints\":[]}"), bundle("{\"endpoints\":[],\"note\":\"new\"}"), Set.of(DB_SCHEMA)))
            .isInstanceOf(IllegalStateException.class);
    }
    @Test void requiresSameSnapshotAndUnchangedSiblings() {
        var before = bundle("{\"endpoints\":[]}");
        var after = new DocumentBundle(1L, UUID.randomUUID(), before.documents());
        assertThatThrownBy(() -> service.analyze(before, after, Set.of(DB_SCHEMA))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }
}
