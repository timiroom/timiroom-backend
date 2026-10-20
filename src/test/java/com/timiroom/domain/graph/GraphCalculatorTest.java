package com.timiroom.domain.graph;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.graph.service.GraphCalculator;
import com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType;
import com.timiroom.domain.spec.dto.DocumentBundle;
import com.timiroom.domain.spec.dto.SpecDocumentDto;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GraphCalculatorTest {
    final GraphCalculator calculator=new GraphCalculator(new ObjectMapper());
    @Test void historicalGraphUsesOnlySuppliedDocuments(){
        var bundle=bundle(1L,"/api/v1/items","items");
        var graph=calculator.calculate(bundle,bundle,List.of());
        assertThat(graph.summary().apiCount()).isEqualTo(1);
        assertThat(graph.summary().tableCount()).isEqualTo(1);
        assertThat(graph.nodes()).anySatisfy(n->assertThat(n.label()).isEqualTo("POST /api/v1/items"));
        assertThat(graph.summary().changedCount()).isZero();
    }
    @Test void changedTableAndEndpointAreComparedAgainstTheProvidedBaseline(){
        var before=bundle(1L,"/api/v1/items","items");
        var after=bundle(1L,"/api/v1/foods","foods");
        var graph=calculator.calculate(before,after,List.of());
        assertThat(graph.nodes()).anySatisfy(n->{assertThat(n.label()).isEqualTo("foods");assertThat(n.change()).isEqualTo("ADDED");});
        assertThat(graph.nodes()).anySatisfy(n->{assertThat(n.label()).isEqualTo("items");assertThat(n.change()).isEqualTo("REMOVED");});
    }
    @Test void graphsCannotMixProjects(){
        assertThatThrownBy(()->calculator.calculate(bundle(1L,"/items","items"),bundle(2L,"/items","items"),List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
    private DocumentBundle bundle(Long projectId,String path,String table){
        return new DocumentBundle(projectId,UUID.randomUUID(),List.of(
            doc(ArtifactType.FEATURE_LIST,"[\"식재료 등록\"]"),
            doc(ArtifactType.API_SPEC,"{\"endpoints\":[{\"method\":\"POST\",\"path\":\""+path+"\",\"description\":\"식재료 등록\"}]}"),
            doc(ArtifactType.DB_SCHEMA,"{\"tables\":[{\"name\":\""+table+"\",\"columns\":[{\"name\":\"id\"}]}]}")));
    }
    private SpecDocumentDto doc(ArtifactType type,String content){return new SpecDocumentDto(type,1L,2L,1,"fixture",content);}
}
