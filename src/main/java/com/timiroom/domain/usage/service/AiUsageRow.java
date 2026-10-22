package com.timiroom.domain.usage.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** AI 서버가 돌려준 사용량 한 줄(같은 종류·제공자·모델·사용자 키 여부끼리 합친 값). */
public record AiUsageRow(String kind, String provider, String model, boolean byok,
                         long inputTokens, long outputTokens, int calls) {

    /** [{"kind":"chat","provider":"server","model":"...","byok":false,"inputTokens":1,"outputTokens":2,"calls":1}] */
    public static List<AiUsageRow> listFrom(JsonNode node) {
        List<AiUsageRow> rows = new ArrayList<>();
        if (node == null || !node.isArray()) return rows;
        for (JsonNode item : node) {
            rows.add(new AiUsageRow(
                    item.path("kind").asText("chat"),
                    item.path("provider").asText("server"),
                    item.path("model").asText(""),
                    item.path("byok").asBoolean(false),
                    Math.max(0, item.path("inputTokens").asLong(0)),
                    Math.max(0, item.path("outputTokens").asLong(0)),
                    Math.max(1, item.path("calls").asInt(1))));
        }
        return rows;
    }
}
