package com.timiroom.domain.spec.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.Optional;

/**
 * 처음 생성된 프로젝트의 기능 목록(FEATURE_LIST)에는 기능 이름만 들어 있다(["포스터 일정 자동 추출"]).
 * 화면(기능 명세)은 이때 기능 상세(FEATURE_SPEC) → PRD 핵심 기능(coreFeatures) 순으로 보여 주므로,
 * 그대로 발행하면 확정 명세와 MCP로 읽는 내용이 사용자가 보는 기능 명세와 달라진다.
 * 발행 전에 이름만 있는 기능 목록을 화면과 같은 상세 기능 배열로 바꿀 내용을 만든다.
 */
public final class FeatureListPromotion {
    private FeatureListPromotion() {}

    /** 바꿀 필요가 없거나 채울 상세가 없으면 비어 있는 값을 돌려준다. */
    public static Optional<String> promote(String featureList, String featureSpec, String prd, ObjectMapper mapper) {
        try {
            if (!namesOnly(read(featureList, mapper))) return Optional.empty();
            ArrayNode detailed = features(read(featureSpec, mapper), "features");
            if (detailed == null || detailed.isEmpty()) detailed = features(read(prd, mapper), "coreFeatures");
            if (detailed == null || detailed.isEmpty()) return Optional.empty();
            return Optional.of(mapper.writeValueAsString(detailed));
        } catch (Exception unreadable) {
            return Optional.empty();
        }
    }

    private static JsonNode read(String json, ObjectMapper mapper) throws Exception {
        return json == null || json.isBlank() ? null : mapper.readTree(json);
    }

    /** 이름 문자열만 있는 기능 목록인지(배열 자체이거나 features/featureList 배열) */
    public static boolean namesOnly(JsonNode node) {
        JsonNode list = node == null ? null : node.isArray() ? node
            : node.has("features") ? node.get("features") : node.get("featureList");
        if (list == null || !list.isArray() || list.isEmpty()) return false;
        for (JsonNode item : list) if (!item.isTextual()) return false;
        return true;
    }

    private static ArrayNode features(JsonNode node, String key) {
        if (node == null) return null;
        JsonNode list = node.isArray() ? node : node.get(key);
        if (list == null || !list.isArray()) return null;
        ArrayNode objects = ((ArrayNode) list).arrayNode();
        for (JsonNode item : list) if (item.isObject() && item.hasNonNull("name")) objects.add(item);
        return objects;
    }
}
