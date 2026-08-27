package com.timiroom.domain.graph.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.github.GithubPullRequestReviewRecord;
import com.timiroom.domain.github.GithubPullRequestReviewRecordRepository;
import com.timiroom.domain.github.ProjectRepoLink;
import com.timiroom.domain.github.ProjectRepoLinkRepository;
import com.timiroom.domain.github.dto.PullRequestTouchPoints;
import com.timiroom.domain.graph.dto.GraphResponse;
import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import com.timiroom.domain.pipeline.repository.ArtifactRevisionRepository;
import com.timiroom.domain.pipeline.service.PipelineService;
import com.timiroom.domain.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 지식 그래프 — 기능 · API · DB 테이블의 연결 관계를 계산한다.
 *
 * 그래프 전용 저장소를 두지 않고 요청 시점에 아티팩트에서 계산한다.
 * 명세가 수정되면 그래프도 즉시 따라오므로 둘 사이가 어긋날 여지가 없다.
 *
 * 연결 규칙:
 *   기능 → API    설명·경로에 기능의 핵심 단어가 얼마나 등장하는지로 판단
 *   API  → 테이블  경로의 리소스명과 테이블명을 대조
 *   테이블 → 테이블 relationships 문자열과 외래키 컬럼에서 추출
 *   PR   → API·테이블  정합성 검사 때 변경 diff에서 뽑아 둔 접점
 *
 * 마지막 규칙이 코드와 기획을 잇는다. PR을 고르면 그 코드가 닿는 API가 나오고,
 * 그 API를 구현하는 기능(요구사항)까지 선이 이어져 "이 변경이 무엇을 건드리는지"가 드러난다.
 *
 * 어느 쪽과도 연결되지 않은 노드는 orphan으로 표시한다.
 * "명세에는 있는데 기능 목록에 근거가 없는 API" 같은 설계 구멍이 여기서 드러난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGraphService {

    private final ProjectService projectService;
    private final PipelineService pipelineService;
    private final ArtifactRevisionRepository revisionRepository;
    private final GithubPullRequestReviewRecordRepository reviewRecordRepository;
    private final ProjectRepoLinkRepository projectRepoLinkRepository;
    private final ObjectMapper objectMapper;

    /** 기능의 핵심 단어 중 이 비율 이상이 API 쪽에 등장하면 연결로 본다 */
    private static final double FEATURE_MATCH_RATIO = 0.5;

    /** 이 이하로 짧은 기능명은 비율 대신 전부 일치를 요구한다 */
    private static final int SHORT_FEATURE_TOKENS = 2;

    /** 이보다 적게 모이면 묶지 않는다 — 혼자인 묶음은 묶음이 아니다 */
    private static final int MIN_GROUP_MEMBERS = 2;

    /** 이 비율을 넘는 API 설명에 등장하는 낱말은 변별력이 없다고 본다 */
    private static final double GENERIC_TOKEN_RATIO = 0.4;

    /** 엔드포인트가 이보다 적으면 낱말 빈도가 우연이라 흔함을 따지지 않는다 */
    private static final int MIN_CORPUS_FOR_TOKEN_FREQUENCY = 8;

    /** 조사·접미사가 붙어도 걸리도록 부분 일치를 쓰므로, 너무 짧은 단어는 오검출을 만든다 */
    private static final int MIN_TOKEN_LENGTH = 2;

    /** 경로에서 리소스가 아닌 세그먼트 */
    private static final Set<String> PATH_NOISE = Set.of("api", "v1", "v2", "v3");

    /**
     * 영향이 번져 나가는 최대 거리.
     *
     * 2로 잡은 건 기획 → API → 테이블이 세 계층이기 때문이다. 어느 계층에서 시작하든
     * 두 걸음이면 위아래 끝에 닿으므로, 한 변경이 실제로 걸리는 범위는 전부 덮인다.
     * 세 걸음부터는 외래키를 타고 옆 도메인으로 새기 시작해 신호가 소음이 된다.
     */
    private static final int MAX_IMPACT_DEPTH = 2;

    /** "테이블A (1:N) 테이블B" 형태의 관계 서술 */
    private static final Pattern RELATION = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)\\s*\\(\\s*([0-9NM]+\\s*:\\s*[0-9NM]+)\\s*\\)\\s*([A-Za-z_][A-Za-z0-9_]*)");

    /**
     * @param memberId 요청자. 이 프로젝트 사람이 맞는지 먼저 확인한다 —
     *                 응답이 곧 명세 전체라 소속 확인 없이는 남의 프로젝트가 그대로 읽힌다.
     */
    @Transactional(readOnly = true)
    public GraphResponse build(Long projectId, Long memberId) {
        projectService.getById(projectId, memberId);
        return build(projectId);
    }

    @Transactional(readOnly = true)
    public GraphResponse build(Long projectId) {
        Map<PipelineArtifact.ArtifactType, JsonNode> artifacts = loadArtifacts(projectId);
        Map<PipelineArtifact.ArtifactType, JsonNode> previous = loadPreviousArtifacts(projectId);

        List<String> features = readFeatures(artifacts.get(PipelineArtifact.ArtifactType.FEATURE_LIST));
        List<ApiNode> apis = readApis(artifacts.get(PipelineArtifact.ArtifactType.API_SPEC));
        List<TableNode> tables = readTables(artifacts.get(PipelineArtifact.ArtifactType.DB_SCHEMA));

        List<GraphResponse.Node> nodes = new ArrayList<>();
        List<GraphResponse.Edge> edges = new ArrayList<>();

        // 연결된 노드를 기록해 두고, 끝까지 남은 것을 orphan으로 처리한다
        Set<String> connected = new LinkedHashSet<>();

        // ── 기능 → API ──────────────────────────────────────────────
        // 이 명세 안에서 흔해빠진 낱말을 먼저 가려낸다. 어느 설명에나 나오는 말은
        // 무엇을 가리키는지 좁혀 주지 못하므로 연결의 근거로 쓰면 안 된다.
        Set<String> genericTokens = findGenericTokens(features, apis);

        for (String feature : features) {
            String featureId = "feature:" + feature;
            for (ApiNode api : apis) {
                if (matchesFeature(feature, api, genericTokens)) {
                    edges.add(new GraphResponse.Edge(
                            "e:" + featureId + "->" + api.id, featureId, api.id, "IMPLEMENTS"));
                    connected.add(featureId);
                    connected.add(api.id);
                }
            }
        }

        // ── API → 테이블 ────────────────────────────────────────────
        for (ApiNode api : apis) {
            for (TableNode table : tables) {
                if (touchesTable(api, table)) {
                    edges.add(new GraphResponse.Edge(
                            "e:" + api.id + "->" + table.id, api.id, table.id, "STORES"));
                    connected.add(api.id);
                    connected.add(table.id);
                }
            }
        }

        // ── 테이블 → 테이블 ─────────────────────────────────────────
        Map<String, TableNode> tableByName = new LinkedHashMap<>();
        tables.forEach(t -> tableByName.put(t.name, t));

        for (Edge relation : readRelations(artifacts.get(PipelineArtifact.ArtifactType.DB_SCHEMA), tableByName)) {
            edges.add(new GraphResponse.Edge(
                    "e:" + relation.from + "->" + relation.to, relation.from, relation.to, "REFERENCES"));
        }

        // ── PR(코드) → API·테이블 ───────────────────────────────────
        // 여기서 코드가 기획에 붙는다. PR이 닿은 API를 거쳐 그 API를 구현하는
        // 기능까지 선이 이어지므로, PR 하나를 고르면 그 변경이 어떤 요구사항에
        // 걸리는지가 그림에서 그대로 따라온다.
        List<PullRequestNode> pullRequests = readPullRequests(projectId);
        Set<String> codeTouched = new LinkedHashSet<>();

        for (PullRequestNode pr : pullRequests) {
            for (ApiNode api : apis) {
                if (pr.touchesApi(api.path)) {
                    edges.add(new GraphResponse.Edge(
                            "e:" + pr.id + "->" + api.id, pr.id, api.id, "CHANGES"));
                    connected.add(api.id);
                    codeTouched.add(api.id);
                }
            }
            for (TableNode table : tables) {
                if (pr.touchesTable(table.name)) {
                    edges.add(new GraphResponse.Edge(
                            "e:" + pr.id + "->" + table.id, pr.id, table.id, "CHANGES"));
                    connected.add(table.id);
                    codeTouched.add(table.id);
                }
            }
        }

        // ── 그룹(도메인) 노드 ───────────────────────────────────────
        // 리소스명을 기준으로 묶는다. reviews · review_scores → review 그룹
        Map<String, String> groupLabels = new LinkedHashMap<>();
        Map<String, String> parentOf = new LinkedHashMap<>();

        for (ApiNode api : apis) {
            String key = api.group;
            groupLabels.putIfAbsent(key, key);
            parentOf.put(api.id, "group:" + key);
        }
        for (TableNode table : tables) {
            String key = table.group;
            groupLabels.putIfAbsent(key, key);
            parentOf.put(table.id, "group:" + key);
        }
        // 기능은 연결된 API의 그룹을 따라간다. 연결이 없으면 묶지 않는다.
        for (String feature : features) {
            String featureId = "feature:" + feature;
            edges.stream()
                    .filter(e -> e.source().equals(featureId))
                    .findFirst()
                    .map(e -> parentOf.get(e.target()))
                    .ifPresent(group -> parentOf.put(featureId, group));
        }

        // 혼자 남는 묶음은 만들지 않는다.
        //
        // 첫 낱말로 묶다 보면 refresh_tokens 하나뿐인 `refresh` 같은 묶음이 생긴다.
        // 구성원이 하나면 그건 묶음이 아니라 노드 옆에 붙은 라벨일 뿐이라, 화면에는
        // 아무것도 알려주지 않으면서 자리만 차지하고 배치까지 끌어당긴다.
        Map<String, Long> memberCount = parentOf.values().stream()
                .collect(Collectors.groupingBy(g -> g, Collectors.counting()));
        parentOf.values().removeIf(group -> memberCount.getOrDefault(group, 0L) < MIN_GROUP_MEMBERS);

        groupLabels.forEach((key, label) -> {
            if (memberCount.getOrDefault("group:" + key, 0L) >= MIN_GROUP_MEMBERS) {
                nodes.add(new GraphResponse.Node(
                        "group:" + key, label, "group", null, false, null, false, Map.of()));
            }
        });

        // ── 변경 판정 ───────────────────────────────────────────────
        // 직전 버전에서 같은 규칙으로 노드를 뽑아 지금과 견준다.
        Changes changes = detectChanges(previous, features, apis, tables);

        // 영향의 출발점은 두 갈래다. 문서를 고쳐서 생긴 변경과, 코드를 고쳐서 생긴 변경.
        // PR이 건드린 API·테이블도 출발점에 넣어야 "이 PR을 머지하면 어디가 흔들리는지"가
        // 문서 변경과 같은 방식으로 그림에 나타난다.
        Set<String> impactSeeds = new LinkedHashSet<>(changes.changedIds());
        impactSeeds.addAll(codeTouched);
        Set<String> impacted = spreadImpact(impactSeeds, edges);

        // ── 노드 생성 ───────────────────────────────────────────────
        int orphanFeatures = 0, orphanApis = 0, orphanTables = 0;

        for (String feature : features) {
            String id = "feature:" + feature;
            boolean orphan = !connected.contains(id);
            if (orphan) orphanFeatures++;
            nodes.add(node(id, feature, "feature", parentOf.get(id), orphan, changes, impacted,
                    Map.of("hint", orphan ? "이 기능을 구현하는 API를 찾지 못했습니다" : "")));
        }

        for (ApiNode api : apis) {
            boolean orphan = !connected.contains(api.id);
            if (orphan) orphanApis++;
            nodes.add(node(api.id, api.method + " " + api.path, "api",
                    parentOf.get(api.id), orphan, changes, impacted,
                    Map.of(
                            "method", api.method,
                            "path", api.path,
                            "description", api.description,
                            "authRequired", api.authRequired,
                            "hint", orphan ? "기능 목록에서 이 API의 근거를 찾지 못했습니다" : "",
                            // 중복은 고아와 달리 연결이 멀쩡해 클릭할 일이 없다.
                            // 그래도 남겨 두어야 명세를 손볼 때 근거가 된다.
                            "notice", api.duplicates > 1
                                    ? "이 엔드포인트가 명세에 " + api.duplicates + "번 적혀 있습니다"
                                    : ""
                    )));
        }

        for (TableNode table : tables) {
            boolean orphan = !connected.contains(table.id);
            if (orphan) orphanTables++;
            nodes.add(node(table.id, table.name, "table",
                    parentOf.get(table.id), orphan, changes, impacted,
                    Map.of(
                            "columns", table.columns,
                            "hint", orphan ? "이 테이블을 사용하는 API를 찾지 못했습니다" : ""
                    )));
        }

        for (PullRequestNode pr : pullRequests) {
            // PR은 orphan으로 세지 않는다. 명세에 걸리는 게 없는 PR은 설계 구멍이 아니라
            // 그냥 리팩터링·설정 변경일 수 있고, 그걸 빨간 신호로 만들면 오히려 신뢰를 잃는다.
            nodes.add(new GraphResponse.Node(pr.id, pr.label, "pr", null, false, null, false,
                    Map.of(
                            "repository", pr.repository,
                            "pullNumber", pr.pullNumber,
                            "url", pr.url,
                            "score", pr.score,
                            "evaluator", pr.evaluator,
                            "warnings", pr.warnings,
                            "files", pr.touched.files(),
                            "reviewUrl", pr.reviewUrl
                    )));
        }

        // 사라진 항목은 지금 목록에 없으므로 따로 노드를 만들어 둔다.
        // "무엇이 없어졌는지"가 영향 확인에서 가장 중요한 정보다.
        for (Map.Entry<String, RemovedNode> removed : changes.removed().entrySet()) {
            RemovedNode r = removed.getValue();
            nodes.add(new GraphResponse.Node(removed.getKey(), r.label(), r.type(), null, false,
                    "REMOVED", false,
                    Map.of("hint", "직전 버전에 있었지만 지금은 없습니다")));
        }

        long changedCount = changes.changedIds().size() + changes.removed().size();
        long impactedCount = impacted.stream().filter(id -> !impactSeeds.contains(id)).count();

        log.info("지식 그래프 생성 | projectId: {}, 노드 {}, 엣지 {} (고아 {}/{}/{}, PR {}, 변경 {}, 영향 {})",
                projectId, nodes.size(), edges.size(),
                orphanFeatures, orphanApis, orphanTables, pullRequests.size(), changedCount, impactedCount);

        return new GraphResponse(nodes, edges, new GraphResponse.Summary(
                features.size(), apis.size(), tables.size(),
                orphanFeatures, orphanApis, orphanTables,
                (int) changedCount, (int) impactedCount, pullRequests.size()));
    }

    /** 변경·영향 표시를 붙여 노드를 만든다 */
    private GraphResponse.Node node(String id, String label, String type, String parent,
                                    boolean orphan, Changes changes, Set<String> impacted,
                                    Map<String, Object> meta) {
        String change = changes.changeOf(id);
        boolean isImpacted = change == null && impacted.contains(id);
        return new GraphResponse.Node(id, label, type, parent, orphan, change, isImpacted, meta);
    }

    /* ══════════════════════════════════════
       변경 감지와 영향 전파
    ══════════════════════════════════════ */

    /**
     * @param added    새로 생긴 노드 id
     * @param modified 내용이 달라진 노드 id
     * @param removed  없어진 노드 — 지금 목록에 없으므로 표시용 정보를 함께 들고 있는다
     */
    private record Changes(Set<String> added, Set<String> modified, Map<String, RemovedNode> removed) {

        Set<String> changedIds() {
            Set<String> all = new LinkedHashSet<>(added);
            all.addAll(modified);
            return all;
        }

        String changeOf(String id) {
            if (added.contains(id)) return "ADDED";
            if (modified.contains(id)) return "MODIFIED";
            return null;
        }
    }

    private record RemovedNode(String label, String type) {}

    /**
     * 직전 버전과 견주어 무엇이 바뀌었는지 가려낸다.
     *
     * 같은 노드인지는 id로 판단하고, 남아 있는 노드는 내용까지 비교한다.
     * API는 설명·인증 여부가, 테이블은 컬럼 구성이 바뀌면 손댄 것으로 본다 —
     * 컬럼이 하나 사라지는 것만으로도 그 테이블을 쓰는 API가 깨질 수 있기 때문이다.
     */
    private Changes detectChanges(Map<PipelineArtifact.ArtifactType, JsonNode> previous,
                                  List<String> features, List<ApiNode> apis, List<TableNode> tables) {

        // 이력이 없으면 비교 대상이 없다 — 전부 "그대로"로 둔다
        if (previous.isEmpty()) {
            return new Changes(Set.of(), Set.of(), Map.of());
        }

        Set<String> added = new LinkedHashSet<>();
        Set<String> modified = new LinkedHashSet<>();
        Map<String, RemovedNode> removed = new LinkedHashMap<>();

        // 문서 종류마다 따로 판단한다.
        // 이력이 없는 문서는 비교 대상이 없으므로 손대지 않는다 —
        // 여기서 통째로 비교하면 한 번도 수정되지 않은 문서의 항목이
        // 전부 "새로 추가됨"으로 잘못 표시된다.

        JsonNode previousFeatures = previous.get(PipelineArtifact.ArtifactType.FEATURE_LIST);
        if (previousFeatures != null) {
            List<String> oldFeatures = readFeatures(previousFeatures);
            Set<String> oldIds = oldFeatures.stream().map(f -> "feature:" + f).collect(Collectors.toSet());
            Set<String> newIds = features.stream().map(f -> "feature:" + f).collect(Collectors.toSet());

            newIds.stream().filter(id -> !oldIds.contains(id)).forEach(added::add);
            for (String feature : oldFeatures) {
                String id = "feature:" + feature;
                if (!newIds.contains(id)) removed.put(id, new RemovedNode(feature, "feature"));
            }
        }

        JsonNode previousApis = previous.get(PipelineArtifact.ArtifactType.API_SPEC);
        if (previousApis != null) {
            Map<String, ApiNode> oldById = readApis(previousApis).stream()
                    .collect(Collectors.toMap(a -> a.id, a -> a, (a, b) -> a, LinkedHashMap::new));

            for (ApiNode api : apis) {
                ApiNode before = oldById.get(api.id);
                if (before == null) {
                    added.add(api.id);
                } else if (!before.description.equals(api.description)
                        || before.authRequired != api.authRequired) {
                    modified.add(api.id);
                }
            }
            Set<String> newIds = apis.stream().map(a -> a.id).collect(Collectors.toSet());
            oldById.forEach((id, a) -> {
                if (!newIds.contains(id)) removed.put(id, new RemovedNode(a.method + " " + a.path, "api"));
            });
        }

        JsonNode previousTables = previous.get(PipelineArtifact.ArtifactType.DB_SCHEMA);
        if (previousTables != null) {
            Map<String, TableNode> oldById = readTables(previousTables).stream()
                    .collect(Collectors.toMap(t -> t.id, t -> t, (a, b) -> a, LinkedHashMap::new));

            for (TableNode table : tables) {
                TableNode before = oldById.get(table.id);
                if (before == null) {
                    added.add(table.id);
                } else if (!before.columns.equals(table.columns)) {
                    modified.add(table.id);
                }
            }
            Set<String> newIds = tables.stream().map(t -> t.id).collect(Collectors.toSet());
            oldById.forEach((id, t) -> {
                if (!newIds.contains(id)) removed.put(id, new RemovedNode(t.name, "table"));
            });
        }

        return new Changes(added, modified, removed);
    }

    /**
     * 바뀐 노드에서 연결을 따라가며 확인이 필요한 노드를 모은다.
     *
     * 방향을 한쪽으로만 보지 않는다. 테이블이 바뀌면 그것을 쓰는 API(들어오는 방향)가
     * 영향을 받고, 기능이 바뀌면 그것을 구현한 API(나가는 방향)가 영향을 받기 때문이다.
     * 양방향으로 퍼뜨리되 이미 담은 노드는 다시 타지 않아 순환에도 멈춘다.
     *
     * 다만 끝까지 퍼뜨리지는 않는다. 테이블끼리 외래키로 이어진 실제 스키마에서는
     * users 하나만 건드려도 온 그래프가 한 덩어리로 이어져, 컬럼 하나 늘렸는데
     * 절반이 "확인 필요"가 된다. 전부 빨간불이면 아무것도 빨간불이 아닌 것과 같다.
     */
    private Set<String> spreadImpact(Set<String> changedIds, List<GraphResponse.Edge> edges) {
        if (changedIds.isEmpty()) return Set.of();

        Map<String, List<String>> neighbours = new LinkedHashMap<>();
        for (GraphResponse.Edge edge : edges) {
            neighbours.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.target());
            neighbours.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge.source());
        }

        Set<String> visited = new LinkedHashSet<>(changedIds);
        Deque<String> queue = new ArrayDeque<>(changedIds);
        Map<String, Integer> depthOf = new LinkedHashMap<>();
        changedIds.forEach(id -> depthOf.put(id, 0));

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int depth = depthOf.getOrDefault(current, 0);
            if (depth >= MAX_IMPACT_DEPTH) continue;

            for (String next : neighbours.getOrDefault(current, List.of())) {
                if (visited.add(next)) {
                    depthOf.put(next, depth + 1);
                    queue.add(next);
                }
            }
        }
        return visited;
    }

    /* ══════════════════════════════════════
       연결 판정
    ══════════════════════════════════════ */

    /**
     * 기능이 이 API로 구현되는지 판단한다.
     *
     * 한국어는 조사가 붙어 형태가 달라지므로("검증" vs "검증된") 정확히 같은 낱말을
     * 찾지 않고 부분 일치로 센다. 핵심 단어의 절반 이상이 API 설명·경로에 등장하면
     * 같은 대상을 말하는 것으로 본다.
     *
     * 다만 낱말이 적으면 비율만으로는 못 믿는다. 두 낱말짜리 기능은 하나만 겹쳐도
     * 곧바로 50%가 되기 때문이다. 실제로 "쿠폰 발급"이 "로그인: 사용자 인증 및 JWT 발급"에
     * `발급` 하나로 걸려 엉뚱하게 이어졌다. 여섯 낱말 중 셋이 겹치는 것과 둘 중 하나가
     * 겹치는 것은 같은 50%라도 증거의 무게가 다르다.
     * 그래서 짧은 기능명은 낱말이 모두 나와야 같은 대상으로 본다.
     *
     * 흔한 낱말은 아예 세지 않는다. `사용자`·`정보` 같은 말은 이 명세의 거의 모든
     * 설명에 나와서 어느 API를 가리키는지 조금도 좁혀 주지 못한다. 실제로
     * "사용자 정보 조회"가 "식성 프로필: 등록된 사용자별 선호 음식 정보 …"에
     * `사용자`·`정보` 둘로 걸려 엉뚱하게 이어졌다.
     */
    private boolean matchesFeature(String feature, ApiNode api, Set<String> genericTokens) {
        List<String> tokens = tokenize(feature);
        if (tokens.isEmpty()) return false;

        List<String> distinctive = tokens.stream().filter(t -> !genericTokens.contains(t)).toList();
        // 남는 게 없으면 이 기능은 흔한 말로만 이루어져 있다는 뜻이다.
        // 그럴 때는 원래 낱말을 그대로 쓰되 전부 나와야 한다고 본다 — 근거가 약할수록 엄하게.
        List<String> judged = distinctive.isEmpty() ? tokens : distinctive;

        String haystack = (api.description + " " + api.path).toLowerCase();
        long hit = judged.stream().filter(haystack::contains).count();

        if (judged.size() <= SHORT_FEATURE_TOKENS) return hit == judged.size();
        return (double) hit / judged.size() >= FEATURE_MATCH_RATIO;
    }

    /**
     * 이 명세 안에서 변별력을 잃은 낱말을 고른다.
     *
     * 절반 가까운 API 설명에 나오는 말은 "여기 있다"는 사실이 아무것도 알려주지 않는다.
     * 문서 빈도가 높을수록 정보량이 적다는, 검색에서 오래 쓰인 생각을 그대로 쓴다.
     *
     * 엔드포인트가 몇 개뿐이면 빈도 자체가 우연이라 적용하지 않는다.
     * 두 개짜리 명세에서는 한 번만 나와도 곧바로 50%가 되기 때문이다.
     */
    private Set<String> findGenericTokens(List<String> features, List<ApiNode> apis) {
        if (apis.size() < MIN_CORPUS_FOR_TOKEN_FREQUENCY) return Set.of();

        List<String> haystacks = apis.stream()
                .map(api -> (api.description + " " + api.path).toLowerCase())
                .toList();

        Set<String> candidates = features.stream()
                .flatMap(feature -> tokenize(feature).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> generic = new LinkedHashSet<>();
        for (String token : candidates) {
            long appearsIn = haystacks.stream().filter(text -> text.contains(token)).count();
            if ((double) appearsIn / haystacks.size() > GENERIC_TOKEN_RATIO) generic.add(token);
        }
        return generic;
    }

    /** API 경로가 이 테이블을 다루는지 — 경로의 리소스명과 테이블명을 대조한다 */
    private boolean touchesTable(ApiNode api, TableNode table) {
        String name = wordSeparated(table.name);
        for (String rawSegment : resourceSegments(api.path)) {
            String segment = wordSeparated(rawSegment);
            if (name.equals(segment)) return true;
            if (name.equals(singular(segment)) || singular(name).equals(singular(segment))) return true;
            // reviews 경로가 review_scores 같은 하위 테이블도 포함하도록
            if (name.startsWith(singular(segment) + "_")) return true;
        }
        return false;
    }

    /**
     * 낱말 구분자를 밑줄로 통일한다.
     *
     * REST 경로는 하이픈(`/food-profiles`)을, DB 테이블은 밑줄(`food_profiles`)을 쓰는 것이
     * 양쪽의 관례다. 글자만 놓고 비교하면 같은 대상을 가리키는데도 어긋나서, 멀쩡히 이어진
     * 테이블이 "이 테이블을 쓰는 API가 없다"는 설계 구멍으로 잘못 보고된다.
     */
    private String wordSeparated(String word) {
        return word.toLowerCase().replace('-', '_');
    }

    /* ══════════════════════════════════════
       아티팩트 읽기
    ══════════════════════════════════════ */

    /**
     * 각 아티팩트의 직전 버전을 읽는다. 이력이 없는 문서는 결과에 담기지 않는다.
     * 한 번도 수정된 적 없는 프로젝트라면 비어 있어 변경 비교를 건너뛴다.
     */
    private Map<PipelineArtifact.ArtifactType, JsonNode> loadPreviousArtifacts(Long projectId) {
        Map<PipelineArtifact.ArtifactType, JsonNode> result = new LinkedHashMap<>();
        for (PipelineArtifact artifact : pipelineService.getLatestArtifactsByProject(projectId)) {
            revisionRepository.findFirstByArtifactIdOrderByVersionDesc(artifact.getArtifactId())
                    .ifPresent(revision -> {
                        try {
                            result.putIfAbsent(artifact.getArtifactType(),
                                    objectMapper.readTree(revision.getContent()));
                        } catch (Exception e) {
                            log.warn("이전 버전 파싱 실패 | artifactId: {}", artifact.getArtifactId());
                        }
                    });
        }
        return result;
    }

    /**
     * 이 프로젝트에 연결된 레포에서 정합성 검사를 마친 PR을 읽는다.
     *
     * 검사 기록이 없는 PR은 그래프에 올리지 않는다. 접점을 뽑아 둔 시점이 곧 검사 시점이라
     * 기록이 없으면 무엇을 건드렸는지 알 수 없고, 이름만 띄운 노드는 선이 없어 오히려
     * "아무 데도 영향이 없다"는 잘못된 인상을 준다.
     *
     * 닫히거나 머지된 PR도 내린다 — 여기 있는 PR은 "지금 진행 중"이라는 뜻이어야 한다.
     */
    private List<PullRequestNode> readPullRequests(Long projectId) {
        List<Long> repoIds = projectRepoLinkRepository.findByProjectId(projectId).stream()
                .map(ProjectRepoLink::getGithubRepoId)
                .toList();
        if (repoIds.isEmpty()) return List.of();

        List<PullRequestNode> result = new ArrayList<>();
        for (GithubPullRequestReviewRecord record : reviewRecordRepository
                .findByProjectIdAndGithubRepoIdIn(projectId, repoIds)) {
            if (!record.isOpenForGraph()) continue;

            PullRequestTouchPoints touched;
            try {
                touched = objectMapper.readValue(record.getTouchedJson(), PullRequestTouchPoints.class);
            } catch (Exception e) {
                log.warn("PR 접점 파싱 실패 | pr: #{}", record.getPullNumber());
                continue;
            }
            if (touched.apis().isEmpty() && touched.tables().isEmpty()) continue;

            String title = record.getPullTitle() == null ? "" : record.getPullTitle();
            result.add(new PullRequestNode(
                    "pr:" + record.getGithubRepoId() + ":" + record.getPullNumber(),
                    "#" + record.getPullNumber() + (title.isBlank() ? "" : " " + title),
                    record.getPullNumber(),
                    record.getPullUrl() == null ? "" : record.getPullUrl(),
                    record.getReviewUrl() == null ? "" : record.getReviewUrl(),
                    String.valueOf(record.getGithubRepoId()),
                    record.getScore() == null ? 0 : record.getScore(),
                    record.getEvaluator() == null ? "" : record.getEvaluator(),
                    countWarnings(record.getFindingsJson()),
                    touched));
        }
        return result;
    }

    /** 경고 건수만 센다 — 그래프에는 몇 건인지가 필요하고, 본문은 명세 패널이 이미 보여준다 */
    private int countWarnings(String findingsJson) {
        if (findingsJson == null) return 0;
        try {
            int warnings = 0;
            for (JsonNode finding : objectMapper.readTree(findingsJson)) {
                if ("WARNING".equalsIgnoreCase(finding.path("severity").asText())) warnings++;
            }
            return warnings;
        } catch (Exception e) {
            return 0;
        }
    }

    private Map<PipelineArtifact.ArtifactType, JsonNode> loadArtifacts(Long projectId) {
        Map<PipelineArtifact.ArtifactType, JsonNode> result = new LinkedHashMap<>();
        for (PipelineArtifact artifact : pipelineService.getLatestArtifactsByProject(projectId)) {
            try {
                result.putIfAbsent(artifact.getArtifactType(), objectMapper.readTree(artifact.getContent()));
            } catch (Exception e) {
                log.warn("아티팩트 파싱 실패 | type: {}, projectId: {}", artifact.getArtifactType(), projectId);
            }
        }
        return result;
    }

    /** FEATURE_LIST는 배열로 저장되기도 하고 {"featureList": [...]} 로 감싸지기도 한다 */
    private List<String> readFeatures(JsonNode root) {
        List<String> features = new ArrayList<>();
        if (root == null) return features;

        JsonNode array = root.isArray() ? root : root.path("featureList");
        if (!array.isArray()) return features;

        for (JsonNode item : array) {
            String value = item.isTextual() ? item.asText() : item.path("featureName").asText("");
            if (!value.isBlank()) features.add(value.trim());
        }
        return features;
    }

    /**
     * API 명세에서 엔드포인트를 읽는다.
     *
     * 같은 method·path가 두 번 적혀 있는 일이 실제로 있다. 그대로 두면 id가 같은 노드가
     * 둘 생기고, 화면 라이브러리가 뒤엣것을 말없이 버려 "노드 수는 맞는데 그림에는 하나"인
     * 상태가 된다. 조용히 사라지는 건 가장 나쁜 실패라, 여기서 합치고 몇 번 적혔는지를
     * 남겨 명세를 손볼 근거로 쓴다.
     */
    private List<ApiNode> readApis(JsonNode root) {
        Map<String, ApiNode> byId = new LinkedHashMap<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        if (root == null) return List.of();

        JsonNode endpoints = root.isArray() ? root : root.path("endpoints");
        if (!endpoints.isArray()) return List.of();

        for (JsonNode endpoint : endpoints) {
            String method = endpoint.path("method").asText("GET").toUpperCase();
            String path = endpoint.path("path").asText("");
            if (path.isBlank()) continue;

            String id = "api:" + method + ":" + path;
            seen.merge(id, 1, Integer::sum);

            // 먼저 적힌 쪽을 남긴다 — 뒤에 붙은 중복은 대개 설명이 짧아진 사본이다
            byId.putIfAbsent(id, new ApiNode(
                    id,
                    method,
                    path,
                    endpoint.path("description").asText(""),
                    endpoint.path("authRequired").asBoolean(false),
                    groupKeyOfPath(path),
                    1
            ));
        }

        return byId.values().stream()
                .map(api -> api.withDuplicates(seen.getOrDefault(api.id, 1)))
                .toList();
    }

    private List<TableNode> readTables(JsonNode root) {
        List<TableNode> tables = new ArrayList<>();
        if (root == null) return tables;

        JsonNode array = root.isArray() ? root : root.path("tables");
        if (!array.isArray()) return tables;

        for (JsonNode table : array) {
            String name = table.path("name").asText("");
            if (name.isBlank()) continue;

            List<String> columns = new ArrayList<>();
            for (JsonNode column : table.path("columns")) {
                String columnName = column.isTextual() ? column.asText() : column.path("name").asText("");
                if (!columnName.isBlank()) columns.add(columnName);
            }
            tables.add(new TableNode("table:" + name, name, columns, groupKeyOfName(name)));
        }
        return tables;
    }

    /** relationships 서술과 외래키 컬럼에서 테이블 간 참조를 뽑는다 */
    private List<Edge> readRelations(JsonNode root, Map<String, TableNode> tableByName) {
        List<Edge> relations = new ArrayList<>();
        if (root == null) return relations;

        for (JsonNode item : root.path("relationships")) {
            Matcher matcher = RELATION.matcher(item.asText(""));
            while (matcher.find()) {
                TableNode from = tableByName.get(matcher.group(1));
                TableNode to = tableByName.get(matcher.group(3));
                if (from != null && to != null && !from.id.equals(to.id)) {
                    relations.add(new Edge(from.id, to.id));
                }
            }
        }

        // {테이블}_id 컬럼은 그 테이블을 참조한다고 본다
        for (TableNode table : tableByName.values()) {
            for (String column : table.columns) {
                if (!column.endsWith("_id")) continue;
                String referenced = column.substring(0, column.length() - 3);
                tableByName.values().stream()
                        .filter(t -> !t.id.equals(table.id))
                        .filter(t -> singular(t.name).equals(singular(referenced)))
                        .findFirst()
                        .ifPresent(t -> relations.add(new Edge(table.id, t.id)));
            }
        }
        return relations;
    }

    /* ══════════════════════════════════════
       문자열 도우미
    ══════════════════════════════════════ */

    /** 기능 문구를 비교용 단어로 자른다. 너무 짧은 조각은 오검출을 만들어 버린다 */
    private List<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().split("[^가-힣a-z0-9]+"))
                .filter(t -> t.length() >= MIN_TOKEN_LENGTH)
                .toList();
    }

    /** 경로에서 버전·변수를 걷어내고 리소스 세그먼트만 남긴다 */
    private List<String> resourceSegments(String path) {
        return Arrays.stream(path.toLowerCase().split("/"))
                .filter(s -> !s.isBlank())
                .filter(s -> !s.startsWith("{"))
                .filter(s -> !PATH_NOISE.contains(s))
                .toList();
    }

    /**
     * 도메인 묶음의 열쇠 — 첫 낱말 하나.
     *
     * 경로와 테이블 모두 구분자를 통일한 뒤 첫 낱말만 본다.
     * `/food-profiles`와 `food_menus`가 같은 `food` 묶음으로 들어가야
     * 화면에서 한 도메인으로 모인다. 통일하지 않으면 같은 도메인이
     * `food-profile`과 `food`로 갈라져 묶음이 잘게 부서진다.
     */
    private String groupKeyOfPath(String path) {
        List<String> segments = resourceSegments(path);
        return segments.isEmpty() ? "기타" : groupKeyOfName(segments.get(0));
    }

    private String groupKeyOfName(String name) {
        return singular(wordSeparated(name).split("_")[0]);
    }

    /** 영어 복수형을 대략 단수로 맞춘다. 테이블명과 경로를 비교하려면 이 정도는 필요하다 */
    private String singular(String word) {
        if (word.endsWith("ies") && word.length() > 3) return word.substring(0, word.length() - 3) + "y";
        if (word.endsWith("ses") && word.length() > 3) return word.substring(0, word.length() - 2);
        if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 1) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    /* ── 내부 표현 ── */

    /** @param duplicates 명세에 이 엔드포인트가 적힌 횟수. 1이면 정상 */
    private record ApiNode(String id, String method, String path, String description,
                           boolean authRequired, String group, int duplicates) {

        ApiNode withDuplicates(int count) {
            return new ApiNode(id, method, path, description, authRequired, group, count);
        }
    }

    private record TableNode(String id, String name, List<String> columns, String group) {}

    private record Edge(String from, String to) {}

    /** 정합성 검사를 마친 PR 하나 — 코드 쪽 노드 */
    private record PullRequestNode(String id, String label, int pullNumber, String url, String reviewUrl,
                                   String repository, int score, String evaluator, int warnings,
                                   PullRequestTouchPoints touched) {

        /**
         * diff에서 뽑은 경로가 이 엔드포인트를 가리키는지.
         *
         * 양쪽을 그대로 견주지 않는다. 코드에는 `/api/v1/reviews/{reviewId}`가 문자열
         * 상수로 쪼개져 있거나 클래스의 @RequestMapping과 메서드 매핑이 나뉘어 있어,
         * diff에서 건진 조각이 명세의 전체 경로와 정확히 같은 경우는 드물다.
         * 한쪽이 다른 쪽을 품고 있으면 같은 엔드포인트를 말하는 것으로 본다.
         */
        boolean touchesApi(String specPath) {
            String spec = normalizePath(specPath);
            if (spec.isBlank()) return false;
            for (String candidate : touched.apis()) {
                String found = normalizePath(candidate);
                if (found.isBlank()) continue;
                if (spec.equals(found) || spec.startsWith(found) || found.startsWith(spec)) return true;
            }
            return false;
        }

        boolean touchesTable(String tableName) {
            return touched.tables().stream().anyMatch(name -> {
                String bare = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
                return bare.equalsIgnoreCase(tableName);
            });
        }

        /** 경로 변수는 이름이 서로 달라도 같은 자리다 — {reviewId}와 {id}를 같게 본다 */
        private static String normalizePath(String path) {
            if (path == null) return "";
            return path.toLowerCase().replaceAll("\\{[^}]*}", "{}").replaceAll("/+$", "").trim();
        }
    }
}
