package com.timiroom.domain.graph.dto;

import java.util.List;
import java.util.Map;

/**
 * 지식 그래프 응답
 *
 * Cytoscape가 그대로 먹을 수 있는 모양으로 내려준다.
 * 노드는 도메인(회원·리뷰 등)으로 묶여 있고, parent가 그룹 노드를 가리킨다.
 */
public record GraphResponse(
        List<Node> nodes,
        List<Edge> edges,
        Summary summary
) {

    /**
     * @param id     고유 식별자 (예: "api:GET:/api/v1/reviews")
     * @param label  화면에 표시할 이름
     * @param type   feature | api | table | pr | group
     * @param parent 소속 그룹 노드 id (그룹 자신은 null)
     * @param orphan 다른 계층과 연결이 없는 노드 — 설계 불일치 신호
     * @param change 직전 버전 대비 변화. null이면 그대로 (ADDED | MODIFIED | REMOVED)
     * @param impacted 스스로 바뀐 건 아니지만 바뀐 것과 이어져 확인이 필요한 노드
     * @param meta   상세 패널에 쓸 부가 정보
     */
    public record Node(
            String id,
            String label,
            String type,
            String parent,
            boolean orphan,
            String change,
            boolean impacted,
            Map<String, Object> meta
    ) {}

    /**
     * @param source 출발 노드 id
     * @param target 도착 노드 id
     * @param type   IMPLEMENTS(기능→API) | STORES(API→테이블) | REFERENCES(테이블→테이블)
     *               | CHANGES(PR→API·테이블)
     */
    public record Edge(
            String id,
            String source,
            String target,
            String type
    ) {}

    /**
     * 그래프 요약 — 화면 상단에 바로 띄울 수 있는 수치
     *
     * @param orphanFeatures API가 하나도 연결되지 않은 기능 수
     * @param orphanApis     기능 목록에 근거가 없는 API 수
     * @param orphanTables   어떤 API도 쓰지 않는 테이블 수
     */
    /**
     * @param changedCount  직전 버전 대비 실제로 바뀐 노드 수
     * @param impactedCount 문서 변경과 코드 변경 때문에 확인이 필요해진 노드 수
     * @param prCount       그래프에 올라온, 정합성 검사를 마친 PR 수
     */
    public record Summary(
            int featureCount,
            int apiCount,
            int tableCount,
            int orphanFeatures,
            int orphanApis,
            int orphanTables,
            int changedCount,
            int impactedCount,
            int prCount
    ) {}
}
