package com.timiroom.domain.spec.service;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType;

/** Adapts the existing feature-array storage to the editor's section-object contract. */
public final class DocumentShape {
    private DocumentShape() {}
    public static boolean valid(ArtifactType type,JsonNode document) {
        return document!=null && (document.isObject() || (type==ArtifactType.FEATURE_LIST && document.isArray()));
    }
    public static ObjectNode forEditor(ArtifactType type,JsonNode original,ObjectMapper mapper) {
        if(!valid(type,original)) throw new IllegalArgumentException("문서 JSON 형식이 올바르지 않습니다");
        if(type==ArtifactType.FEATURE_LIST && original.isArray()) return mapper.createObjectNode().set("features",original.deepCopy());
        ObjectNode normalized=original.deepCopy();
        if(type==ArtifactType.FEATURE_LIST && normalized.has("featureList") && !normalized.has("features")) {
            normalized.set("features",normalized.remove("featureList"));
        }
        return normalized;
    }
    public static JsonNode restore(ArtifactType type,JsonNode original,JsonNode edited) {
        if(type!=ArtifactType.FEATURE_LIST) return edited;
        if(original.isArray()) {
            if(edited.isArray()) return edited;
            var features=edited.has("features")?edited.get("features"):edited.get("featureList");
            if(features==null || !features.isArray()) throw new IllegalStateException("기능 목록 배열 형식을 바꿀 수 없습니다");
            return features.deepCopy();
        }
        if(original.has("featureList") && !original.has("features") && edited.has("features")) {
            ObjectNode restored=edited.deepCopy();restored.set("featureList",restored.remove("features"));return restored;
        }
        return edited;
    }
}
