package com.timiroom.domain.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.spec.dto.*;
import com.timiroom.domain.spec.service.*;
import com.timiroom.infra.ragpipeline.DocumentEditClient;
import com.timiroom.infra.consistency.ConsistencyServiceClient;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentEditServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final DocumentEditClient client = mock(DocumentEditClient.class);
    final ConsistencyServiceClient consistency=mock(ConsistencyServiceClient.class);
    final DocumentEditService service = new DocumentEditService(client, consistency, mapper);
    @Test void explicitWholeDocumentInstructionUsesExistingRevisionEngine() throws Exception {
        when(consistency.reviseArtifact(anyMap())).thenReturn(mapper.readTree("{\"revisedContent\":{\"endpoints\":[{\"path\":\"/new\"}],\"note\":\"keep\"},\"changeSummary\":\"수정\"}"));
        var result=service.propose(base(),List.of(API_SPEC),"문서 전체 재작성: 경로 수정",List.of("note 보존"));
        assertThat(result.executor()).isEqualTo("CONSISTENCY_REVISION_AGENT");
        verifyNoInteractions(client);verify(consistency).reviseArtifact(anyMap());
    }
    DocumentBundle base() {
        return new DocumentBundle(1L, UUID.randomUUID(), List.of(
            new SpecDocumentDto(API_SPEC, 10L, 5L, 2, "hash", "{\"endpoints\":[{\"path\":\"/old\"}],\"note\":\"keep\"}")));
    }
    @Test void preservesOtherSectionsAndDiff() throws Exception {
        when(client.edit(eq("api"), any(), anyString())).thenReturn(mapper.readTree("""
            {"intent":"edit","reply":"경로 수정","edits":[{"section":"endpoints","before":[{"path":"/old"}],"after":[{"path":"/new"}],"diff":[{"type":"changed"}]}]}
            """));
        var result = service.propose(base(), List.of(API_SPEC), "경로 변경", List.of());
        assertThat(result.changes()).hasSize(1);
        var change = result.changes().getFirst();
        assertThat(change.document().path("note").asText()).isEqualTo("keep");
        assertThat(change.document().path("endpoints").get(0).path("path").asText()).isEqualTo("/new");
        assertThat(change.sectionDiffs().get(0).path("diff").get(0).path("type").asText()).isEqualTo("changed");
    }
    @Test void rejectsDiffWhoseBeforeDoesNotMatchSnapshot() throws Exception {
        when(client.edit(anyString(), any(), anyString())).thenReturn(mapper.readTree("""
            {"intent":"edit","edits":[{"section":"endpoints","before":[],"after":[]}]}
            """));
        assertThatThrownBy(() -> service.propose(base(), List.of(API_SPEC), "변경", List.of()))
            .isInstanceOf(IllegalStateException.class);
    }
    @Test void validatesAllTargetsBeforeAnyProviderCall() {
        assertThatThrownBy(() -> service.propose(base(), List.of(API_SPEC, PRD), "변경", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }
    @Test void mapsOnlySupportedDocumentTypes() {
        assertThat(DocumentEditService.docType(PRD)).isEqualTo("prd");
        assertThat(DocumentEditService.docType(FEATURE_LIST)).isEqualTo("features");
        assertThat(DocumentEditService.docType(API_SPEC)).isEqualTo("api");
        assertThat(DocumentEditService.docType(DB_SCHEMA)).isEqualTo("erd");
        assertThatThrownBy(() -> DocumentEditService.docType(QA_REPORT)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void featureArrayPreservesItsOriginalStorageShape() throws Exception {
        var bundle=new DocumentBundle(1L,UUID.randomUUID(),List.of(new SpecDocumentDto(FEATURE_LIST,10L,5L,1,"h","[{\"name\":\"목록\",\"description\":\"old\"}]")));
        when(client.edit(eq("features"),any(),anyString())).thenReturn(mapper.readTree("""
            {"intent":"edit","reply":"설명 수정","edits":[{"section":"features","before":[{"name":"목록","description":"old"}],"after":[{"name":"목록","description":"new"}],"diff":[]}]}
            """));
        var result=service.propose(bundle,List.of(FEATURE_LIST),"설명 변경",List.of());
        assertThat(result.changes().getFirst().document().isArray()).isTrue();
        assertThat(result.changes().getFirst().document().get(0).path("description").asText()).isEqualTo("new");
        verify(client).edit(eq("features"),argThat(doc->doc.path("features").isArray()),anyString());
    }
}
