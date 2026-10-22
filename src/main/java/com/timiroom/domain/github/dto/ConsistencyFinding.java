package com.timiroom.domain.github.dto;

import java.util.List;

/** 명세 대조 결과 한 건. severity는 PASS, INFO, WARNING, INCONCLUSIVE 중 하나다. */
public record ConsistencyFinding(
        String severity,
        String area,
        String message,
        List<String> evidence,
        List<EvidenceReference> references,
        String recommendation
) {
    public ConsistencyFinding {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        references = references == null ? List.of() : List.copyOf(references);
        if ("WARNING".equals(severity) && (recommendation == null || recommendation.isBlank())) {
            recommendation = "명세를 기준으로 구현을 수정하거나, 구현이 의도된 변경이면 관련 명세를 함께 갱신하세요.";
        }
    }

    public ConsistencyFinding(String severity, String area, String message) {
        this(severity, area, message, List.of(), List.of(), null);
    }

    public ConsistencyFinding(String severity, String area, String message,
                              List<String> evidence, String recommendation) {
        this(severity, area, message, evidence, List.of(), recommendation);
    }
}
