package com.timiroom.domain.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.github.GithubPullRequestReviewRecord;
import com.timiroom.domain.github.GithubPullRequestReviewRecordRepository;
import com.timiroom.domain.github.ProjectRepoLink;
import com.timiroom.domain.github.ProjectRepoLinkRepository;
import com.timiroom.domain.graph.dto.GraphResponse;
import com.timiroom.domain.graph.service.KnowledgeGraphService;
import com.timiroom.domain.pipeline.entity.ArtifactRevision;
import com.timiroom.domain.pipeline.entity.PipelineArtifact;
import com.timiroom.domain.pipeline.repository.ArtifactRevisionRepository;
import com.timiroom.domain.pipeline.service.PipelineService;
import com.timiroom.domain.project.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * 지식 그래프 연결 판정 검증.
 *
 * 실제 파이프라인이 만들어 낸 명세와 같은 모양의 데이터를 쓴다.
 * 한국어 기능 문구와 영어 경로·테이블명을 잇는 부분이 이 기능의 핵심이라
 * 그 판정이 의도대로 도는지를 중심으로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeGraphServiceTest {

    @Mock
    PipelineService pipelineService;

    @Mock
    ArtifactRevisionRepository revisionRepository;

    @Mock
    ProjectService projectService;

    @Mock
    GithubPullRequestReviewRecordRepository reviewRecordRepository;

    @Mock
    ProjectRepoLinkRepository projectRepoLinkRepository;

    KnowledgeGraphService service;

    private static final String FEATURES = """
            ["리뷰 신뢰도 점수", "검증 리뷰 목록 조회", "리뷰 작성", "포인트 정산"]
            """;

    private static final String API_SPEC = """
            {
              "endpoints": [
                {"method":"GET","path":"/api/v1/reviews","description":"검증된 리뷰 목록을 조회한다","authRequired":false},
                {"method":"POST","path":"/api/v1/reviews","description":"리뷰 작성","authRequired":true},
                {"method":"GET","path":"/api/v1/reviews/{reviewId}/score","description":"리뷰 신뢰도 점수 조회","authRequired":false},
                {"method":"GET","path":"/api/v1/banners","description":"홍보 배너 조회","authRequired":false}
              ]
            }
            """;

    private static final String DB_SCHEMA = """
            {
              "tables": [
                {"name":"reviews","columns":[{"name":"id"},{"name":"member_id"},{"name":"content"}]},
                {"name":"review_scores","columns":[{"name":"id"},{"name":"review_id"},{"name":"score"}]},
                {"name":"members","columns":[{"name":"id"},{"name":"nickname"}]},
                {"name":"audit_logs","columns":[{"name":"id"},{"name":"message"}]}
              ],
              "relationships": ["members (1:N) reviews", "reviews (1:N) review_scores"]
            }
            """;

    @BeforeEach
    void setUp() {
        service = new KnowledgeGraphService(projectService, pipelineService, revisionRepository,
                reviewRecordRepository, projectRepoLinkRepository, new ObjectMapper());
    }

    private void givenArtifacts() {
        given(pipelineService.getLatestArtifactsByProject(1L)).willReturn(List.of(
                artifact(PipelineArtifact.ArtifactType.FEATURE_LIST, FEATURES),
                artifact(PipelineArtifact.ArtifactType.API_SPEC, API_SPEC),
                artifact(PipelineArtifact.ArtifactType.DB_SCHEMA, DB_SCHEMA)
        ));
    }

    private PipelineArtifact artifact(PipelineArtifact.ArtifactType type, String content) {
        return PipelineArtifact.builder()
                .artifactId((long) (type.ordinal() + 1))
                .executionId(1L)
                .artifactType(type)
                .content(content)
                .version(2)
                .build();
    }

    @Test
    @DisplayName("기능 문구에 조사가 붙어도 같은 대상을 말하는 API와 이어진다")
    void 기능과_API를_연결한다() {
        givenArtifacts();

        GraphResponse graph = service.build(1L);

        // "검증 리뷰 목록 조회" ↔ "검증된 리뷰 목록을 조회한다" — 조사 차이를 넘어 연결돼야 한다
        assertThat(edgeExists(graph, "feature:검증 리뷰 목록 조회", "api:GET:/api/v1/reviews")).isTrue();
        assertThat(edgeExists(graph, "feature:리뷰 신뢰도 점수", "api:GET:/api/v1/reviews/{reviewId}/score")).isTrue();
        assertThat(edgeExists(graph, "feature:리뷰 작성", "api:POST:/api/v1/reviews")).isTrue();
    }

    @Test
    @DisplayName("짧은 기능명은 낱말이 하나만 겹쳐서는 이어지지 않는다")
    void 짧은_기능명은_전부_일치해야_한다() {
        // 실제 명세에서 나온 사례다. "쿠폰 발급"의 두 낱말 중 `발급` 하나가
        // "로그인: JWT 발급"에 걸려 곧바로 50%가 되면서 엉뚱하게 이어졌다.
        String apis = """
                {"endpoints":[
                  {"method":"POST","path":"/api/v1/auth/login","description":"로그인: 사용자 인증 및 JWT 발급","authRequired":false},
                  {"method":"POST","path":"/api/v1/coupons","description":"쿠폰 발급: 신규 가입자에게 쿠폰 지급","authRequired":true}
                ]}
                """;

        given(pipelineService.getLatestArtifactsByProject(1L)).willReturn(List.of(
                artifact(PipelineArtifact.ArtifactType.FEATURE_LIST, """
                        ["로그인", "쿠폰 발급"]"""),
                artifact(PipelineArtifact.ArtifactType.API_SPEC, apis),
                artifact(PipelineArtifact.ArtifactType.DB_SCHEMA, """
                        {"tables":[]}""")));

        GraphResponse graph = service.build(1L);

        // `발급` 하나만 겹치는 로그인 API와는 이어지지 않는다
        assertThat(edgeExists(graph, "feature:쿠폰 발급", "api:POST:/api/v1/auth/login")).isFalse();
        // 두 낱말이 모두 나오는 쪽과는 이어진다
        assertThat(edgeExists(graph, "feature:쿠폰 발급", "api:POST:/api/v1/coupons")).isTrue();
        // 한 낱말짜리 기능도 그대로 이어져야 한다
        assertThat(edgeExists(graph, "feature:로그인", "api:POST:/api/v1/auth/login")).isTrue();
    }

    @Test
    @DisplayName("거의 모든 설명에 나오는 낱말은 연결의 근거로 세지 않는다")
    void 흔한_낱말은_근거가_되지_않는다() {
        // 실제 명세에서 나온 사례다. `사용자`는 아홉 중 여섯에 나와 어느 API인지
        // 조금도 좁혀 주지 못하는데, "사용자 정보 조회"가 그 낱말에 기대어
        // 엉뚱하게 식성 프로필 API까지 이어졌다.
        String apis = """
                {"endpoints":[
                  {"method":"GET","path":"/api/v1/users/me","description":"사용자 정보 조회: 현재 로그인된 사용자 정보 반환","authRequired":true},
                  {"method":"GET","path":"/api/v1/food-profiles","description":"식성 프로필: 등록된 사용자별 선호 음식 정보 확인","authRequired":true},
                  {"method":"POST","path":"/api/v1/food-profiles","description":"식성 프로필: 사용자별 선호 등록","authRequired":true},
                  {"method":"PUT","path":"/api/v1/food-profiles/{userId}","description":"식성 프로필: 사용자별 선호 정보 수정","authRequired":true},
                  {"method":"POST","path":"/api/v1/auth/register","description":"회원가입: 사용자 계정 생성","authRequired":false},
                  {"method":"POST","path":"/api/v1/auth/login","description":"로그인: 사용자 인증 및 토큰 발급","authRequired":false},
                  {"method":"PUT","path":"/api/v1/users/profile","description":"프로필 수정: 사용자 프로필 정보 업데이트","authRequired":true},
                  {"method":"POST","path":"/api/v1/delivery-estimations","description":"배달 시간 예측: 도착 시간 예측 요청","authRequired":true},
                  {"method":"GET","path":"/api/v1/weekly-reports","description":"주간 추천 리포트: 주간 식사 패턴 분석","authRequired":true}
                ]}
                """;

        given(pipelineService.getLatestArtifactsByProject(1L)).willReturn(List.of(
                artifact(PipelineArtifact.ArtifactType.FEATURE_LIST, """
                        ["사용자 정보 조회", "식성 프로필", "배달 시간 예측"]"""),
                artifact(PipelineArtifact.ArtifactType.API_SPEC, apis),
                artifact(PipelineArtifact.ArtifactType.DB_SCHEMA, """
                        {"tables":[]}""")));

        GraphResponse graph = service.build(1L);

        // 흔한 `사용자`를 빼면 남는 건 `정보`·`조회`. 둘 다 나오는 곳에만 이어진다.
        assertThat(edgeExists(graph, "feature:사용자 정보 조회", "api:GET:/api/v1/users/me")).isTrue();
        assertThat(edgeExists(graph, "feature:사용자 정보 조회", "api:GET:/api/v1/food-profiles")).isFalse();
        assertThat(edgeExists(graph, "feature:사용자 정보 조회", "api:PUT:/api/v1/users/profile")).isFalse();

        // 변별력 있는 낱말로 이루어진 기능은 그대로 이어져야 한다
        assertThat(edgeExists(graph, "feature:식성 프로필", "api:POST:/api/v1/food-profiles")).isTrue();
        assertThat(edgeExists(graph, "feature:배달 시간 예측", "api:POST:/api/v1/delivery-estimations")).isTrue();
    }

    @Test
    @DisplayName("엔드포인트가 얼마 없으면 낱말 빈도를 따지지 않는다")
    void 명세가_작으면_빈도를_따지지_않는다() {
        // 두 개짜리 명세에서는 한 번만 나와도 곧바로 50%라, 빈도가 변별력을 말해 주지 못한다.
        // 이때 흔함을 따지면 멀쩡한 낱말이 통째로 버려진다.
        givenArtifacts();   // 엔드포인트 4개

        GraphResponse graph = service.build(1L);

        assertThat(edgeExists(graph, "feature:검증 리뷰 목록 조회", "api:GET:/api/v1/reviews")).isTrue();
        assertThat(edgeExists(graph, "feature:리뷰 작성", "api:POST:/api/v1/reviews")).isTrue();
    }

    @Test
    @DisplayName("같은 엔드포인트가 명세에 두 번 적혀 있으면 합치고 몇 번인지 남긴다")
    void 중복_엔드포인트를_합친다() {
        // 실제 명세에 GET /api/v2/food-menu/{category}가 두 번 들어 있었다.
        // 합치지 않으면 id가 같은 노드가 둘 생기고 화면이 뒤엣것을 말없이 버린다.
        String apis = """
                {"endpoints":[
                  {"method":"GET","path":"/api/v1/reviews","description":"검증된 리뷰 목록을 조회한다","authRequired":false},
                  {"method":"GET","path":"/api/v1/reviews","description":"리뷰 목록 조회","authRequired":false}
                ]}
                """;

        given(pipelineService.getLatestArtifactsByProject(1L)).willReturn(List.of(
                artifact(PipelineArtifact.ArtifactType.FEATURE_LIST, """
                        ["검증 리뷰 목록 조회"]"""),
                artifact(PipelineArtifact.ArtifactType.API_SPEC, apis),
                artifact(PipelineArtifact.ArtifactType.DB_SCHEMA, """
                        {"tables":[]}""")));

        GraphResponse graph = service.build(1L);

        assertThat(graph.summary().apiCount()).isEqualTo(1);
        assertThat(graph.nodes().stream().map(GraphResponse.Node::id).distinct().count())
                .isEqualTo(graph.nodes().size());
        assertThat(nodeById(graph, "api:GET:/api/v1/reviews").meta())
                .extracting("notice").asString().contains("2번");
        // 먼저 적힌 설명이 남는다
        assertThat(nodeById(graph, "api:GET:/api/v1/reviews").meta())
                .extracting("description").asString().contains("검증된");
    }

    @Test
    @DisplayName("API 경로의 리소스명으로 테이블을 찾고, 같은 계열 하위 테이블도 함께 잇는다")
    void API와_테이블을_연결한다() {
        givenArtifacts();

        GraphResponse graph = service.build(1L);

        assertThat(edgeExists(graph, "api:GET:/api/v1/reviews", "table:reviews")).isTrue();
        // reviews 경로는 review_scores 같은 같은 계열 테이블도 다룬다고 본다
        assertThat(edgeExists(graph, "api:GET:/api/v1/reviews", "table:review_scores")).isTrue();
        // 무관한 테이블까지 잇지는 않는다
        assertThat(edgeExists(graph, "api:GET:/api/v1/reviews", "table:members")).isFalse();
    }

    @Test
    @DisplayName("경로의 하이픈과 테이블의 밑줄은 같은 낱말로 본다")
    void 구분자가_달라도_같은_리소스로_본다() {
        // 실제 명세가 이렇게 생겼다 — REST는 하이픈, DB는 밑줄이 양쪽의 관례다.
        // 글자만 비교하면 멀쩡한 연결이 통째로 "설계 구멍"으로 잘못 보고된다.
        String apis = """
                {"endpoints":[
                  {"method":"GET","path":"/api/v1/food-profiles","description":"식성 프로필 조회","authRequired":true},
                  {"method":"GET","path":"/api/v2/food-menu/{category}","description":"간편 탐색","authRequired":true}
                ]}
                """;
        String schema = """
                {"tables":[
                  {"name":"food_profiles","columns":[{"name":"id"},{"name":"user_id"}]},
                  {"name":"food_menus","columns":[{"name":"id"},{"name":"category"}]}
                ]}
                """;

        given(pipelineService.getLatestArtifactsByProject(1L)).willReturn(List.of(
                artifact(PipelineArtifact.ArtifactType.FEATURE_LIST, """
                        ["식성 프로필", "간편 탐색"]"""),
                artifact(PipelineArtifact.ArtifactType.API_SPEC, apis),
                artifact(PipelineArtifact.ArtifactType.DB_SCHEMA, schema)));

        GraphResponse graph = service.build(1L);

        assertThat(edgeExists(graph, "api:GET:/api/v1/food-profiles", "table:food_profiles")).isTrue();
        assertThat(edgeExists(graph, "api:GET:/api/v2/food-menu/{category}", "table:food_menus")).isTrue();
        assertThat(graph.summary().orphanTables()).isZero();

        // 같은 도메인으로도 묶여야 한다 — food-profiles와 food_menus 모두 food 묶음
        String group = nodeById(graph, "table:food_profiles").parent();
        assertThat(group).isNotNull();
        assertThat(nodeById(graph, "table:food_menus").parent()).isEqualTo(group);
        assertThat(nodeById(graph, "api:GET:/api/v1/food-profiles").parent()).isEqualTo(group);
    }

    @Test
    @DisplayName("관계 서술과 외래키 컬럼에서 테이블 간 참조를 뽑는다")
    void 테이블_간_참조를_연결한다() {
        givenArtifacts();

        GraphResponse graph = service.build(1L);

        assertThat(edgeExists(graph, "table:members", "table:reviews")).isTrue();
        assertThat(edgeExists(graph, "table:reviews", "table:review_scores")).isTrue();
        // member_id 컬럼으로도 members를 참조한다고 본다
        assertThat(hasEdge(graph, "table:reviews", "table:members", "REFERENCES")).isTrue();
    }

    @Test
    @DisplayName("어느 쪽과도 이어지지 않은 노드를 설계 구멍으로 표시한다")
    void 고아_노드를_찾아낸다() {
        givenArtifacts();

        GraphResponse graph = service.build(1L);

        // 기능 목록에 근거가 없는 API
        assertThat(nodeById(graph, "api:GET:/api/v1/banners").orphan()).isTrue();
        // 구현하는 API가 없는 기능
        assertThat(nodeById(graph, "feature:포인트 정산").orphan()).isTrue();
        // 쓰는 API가 없는 테이블
        assertThat(nodeById(graph, "table:audit_logs").orphan()).isTrue();

        // members는 reviews와 외래키로 이어져 있지만 이 테이블을 다루는 API가 없다.
        // 같은 계층끼리의 연결은 고아 판정을 풀어주지 않는다 —
        // 저장만 되고 아무도 꺼내 쓰지 않는 테이블이야말로 설계 구멍이기 때문이다.
        assertThat(nodeById(graph, "table:members").orphan()).isTrue();

        // 정상적으로 이어진 노드는 표시되지 않는다
        assertThat(nodeById(graph, "api:GET:/api/v1/reviews").orphan()).isFalse();

        assertThat(graph.summary().orphanApis()).isEqualTo(1);
        assertThat(graph.summary().orphanFeatures()).isEqualTo(1);
        assertThat(graph.summary().orphanTables()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 리소스를 다루는 노드는 하나의 도메인 그룹으로 묶인다")
    void 도메인별로_묶는다() {
        givenArtifacts();

        GraphResponse graph = service.build(1L);

        String reviewGroup = nodeById(graph, "table:reviews").parent();
        assertThat(reviewGroup).isNotNull();
        // reviews · review_scores · /api/v1/reviews 는 같은 그룹이어야 한다
        assertThat(nodeById(graph, "table:review_scores").parent()).isEqualTo(reviewGroup);
        assertThat(nodeById(graph, "api:GET:/api/v1/reviews").parent()).isEqualTo(reviewGroup);
        // 다른 리소스는 다른 그룹
        assertThat(nodeById(graph, "table:members").parent()).isNotEqualTo(reviewGroup);
    }

    @Test
    @DisplayName("혼자 남는 도메인은 묶지 않는다")
    void 구성원이_하나뿐인_묶음은_만들지_않는다() {
        givenArtifacts();

        GraphResponse graph = service.build(1L);

        // audit_logs 하나뿐인 audit은 묶음이 되지 않는다.
        // 구성원이 하나면 화면에 라벨만 하나 더 뜰 뿐 아무것도 알려주지 않는다.
        assertThat(graph.nodes()).noneMatch(n -> "group:audit".equals(n.id()));
        assertThat(nodeById(graph, "table:audit_logs").parent()).isNull();

        // 여럿이 모인 묶음은 그대로 남는다 — reviews·review_scores·/reviews
        assertThat(graph.nodes()).anyMatch(n -> "group:review".equals(n.id()));
        assertThat(nodeById(graph, "table:reviews").parent()).isEqualTo("group:review");
    }

    @Test
    @DisplayName("명세가 아직 없는 프로젝트는 빈 그래프를 돌려준다")
    void 아티팩트가_없으면_빈_그래프() {
        given(pipelineService.getLatestArtifactsByProject(9L)).willReturn(List.of());

        GraphResponse graph = service.build(9L);

        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.edges()).isEmpty();
        assertThat(graph.summary().featureCount()).isZero();
    }

    /* ══════════════════════════════════════
       변경 영향 — 이 기능의 핵심
    ══════════════════════════════════════ */

    @Test
    @DisplayName("테이블에서 컬럼이 빠지면 그 테이블을 쓰는 API까지 확인 대상이 된다")
    void 변경이_이어진_곳까지_퍼진다() {
        // 직전 버전에는 reviews에 content 컬럼이 있었다
        String beforeSchema = DB_SCHEMA;
        // 지금은 content가 빠졌다 — 이 테이블을 읽어 쓰던 API가 깨질 수 있다
        String afterSchema = DB_SCHEMA.replace("""
                {"name":"reviews","columns":[{"name":"id"},{"name":"member_id"},{"name":"content"}]}""",
                """
                {"name":"reviews","columns":[{"name":"id"},{"name":"member_id"}]}""");

        given(pipelineService.getLatestArtifactsByProject(1L)).willReturn(List.of(
                artifact(PipelineArtifact.ArtifactType.FEATURE_LIST, FEATURES),
                artifact(PipelineArtifact.ArtifactType.API_SPEC, API_SPEC),
                artifact(PipelineArtifact.ArtifactType.DB_SCHEMA, afterSchema)
        ));
        givenRevision(PipelineArtifact.ArtifactType.DB_SCHEMA, beforeSchema);

        GraphResponse graph = service.build(1L);

        // 직접 바뀐 것
        assertThat(nodeById(graph, "table:reviews").change()).isEqualTo("MODIFIED");

        // 그 테이블을 쓰는 API는 스스로 바뀌지 않았지만 확인이 필요하다
        assertThat(nodeById(graph, "api:GET:/api/v1/reviews").impacted()).isTrue();
        assertThat(nodeById(graph, "api:POST:/api/v1/reviews").impacted()).isTrue();

        // 그 API를 낳은 기능까지 이어져 올라간다
        assertThat(nodeById(graph, "feature:리뷰 작성").impacted()).isTrue();

        // 무관한 쪽은 건드리지 않는다
        assertThat(nodeById(graph, "api:GET:/api/v1/banners").impacted()).isFalse();

        assertThat(graph.summary().changedCount()).isEqualTo(1);
        assertThat(graph.summary().impactedCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("영향은 두 걸음까지만 번져 옆 도메인으로 새지 않는다")
    void 영향은_두_걸음까지만_번진다() {
        // members 테이블이 바뀐 상황. members는 reviews와 외래키로 이어져 있고
        // reviews는 다시 여러 API·기능으로 이어진다. 끝까지 퍼뜨리면 온 그래프가 물든다.
        String beforeSchema = DB_SCHEMA;
        String afterSchema = DB_SCHEMA.replace("""
                {"name":"members","columns":[{"name":"id"},{"name":"nickname"}]}""",
                """
                {"name":"members","columns":[{"name":"id"}]}""");

        given(pipelineService.getLatestArtifactsByProject(1L)).willReturn(List.of(
                artifact(PipelineArtifact.ArtifactType.FEATURE_LIST, FEATURES),
                artifact(PipelineArtifact.ArtifactType.API_SPEC, API_SPEC),
                artifact(PipelineArtifact.ArtifactType.DB_SCHEMA, afterSchema)));
        givenRevision(PipelineArtifact.ArtifactType.DB_SCHEMA, beforeSchema);

        GraphResponse graph = service.build(1L);

        assertThat(nodeById(graph, "table:members").change()).isEqualTo("MODIFIED");

        // 한 걸음: 외래키로 이어진 reviews
        assertThat(nodeById(graph, "table:reviews").impacted()).isTrue();

        // 세 걸음 거리인 기능까지는 가지 않는다.
        // members → reviews(1) → GET /reviews(2) → 검증 리뷰 목록 조회(3)
        assertThat(nodeById(graph, "feature:검증 리뷰 목록 조회").impacted()).isFalse();
    }

    @Test
    @DisplayName("기능을 새로 넣으면 추가로 표시된다")
    void 추가된_항목을_찾아낸다() {
        String beforeFeatures = """
                ["리뷰 신뢰도 점수", "검증 리뷰 목록 조회", "포인트 정산"]
                """;

        givenArtifacts();
        givenRevision(PipelineArtifact.ArtifactType.FEATURE_LIST, beforeFeatures);

        GraphResponse graph = service.build(1L);

        assertThat(nodeById(graph, "feature:리뷰 작성").change()).isEqualTo("ADDED");
        assertThat(nodeById(graph, "feature:포인트 정산").change()).isNull();
    }

    @Test
    @DisplayName("사라진 항목도 노드로 남겨 무엇이 없어졌는지 보이게 한다")
    void 삭제된_항목을_남긴다() {
        String beforeApis = API_SPEC.replace("""
                {"method":"GET","path":"/api/v1/banners","description":"홍보 배너 조회","authRequired":false}""",
                """
                {"method":"GET","path":"/api/v1/banners","description":"홍보 배너 조회","authRequired":false},
                {"method":"DELETE","path":"/api/v1/reviews/{reviewId}","description":"리뷰 삭제","authRequired":true}""");

        givenArtifacts();
        givenRevision(PipelineArtifact.ArtifactType.API_SPEC, beforeApis);

        GraphResponse graph = service.build(1L);

        GraphResponse.Node gone = nodeById(graph, "api:DELETE:/api/v1/reviews/{reviewId}");
        assertThat(gone.change()).isEqualTo("REMOVED");
        assertThat(gone.label()).contains("DELETE");
    }

    @Test
    @DisplayName("수정된 적이 없으면 변경 표시가 생기지 않는다")
    void 이력이_없으면_변경도_없다() {
        givenArtifacts();   // 이력 없음

        GraphResponse graph = service.build(1L);

        assertThat(graph.summary().changedCount()).isZero();
        assertThat(graph.summary().impactedCount()).isZero();
        assertThat(graph.nodes()).allSatisfy(n -> {
            assertThat(n.change()).isNull();
            assertThat(n.impacted()).isFalse();
        });
    }

    @Test
    @DisplayName("프로젝트 사람이 아니면 그래프를 내주지 않는다")
    void 소속을_먼저_확인한다() {
        // 응답에는 기능 목록·API 경로와 설명·테이블 컬럼·PR 제목이 모두 담긴다.
        // 사실상 명세 전체라, 소속 확인 없이는 projectId만 바꿔 남의 프로젝트가 읽힌다.
        given(projectService.getById(1L, 999L))
                .willThrow(new SecurityException("프로젝트 접근 권한이 없습니다"));

        assertThatThrownBy(() -> service.build(1L, 999L))
                .isInstanceOf(SecurityException.class);

        // 권한이 막혔으면 명세를 읽는 데까지 가서도 안 된다
        then(pipelineService).should(never()).getLatestArtifactsByProject(anyLong());
    }

    @Test
    @DisplayName("프로젝트 사람이면 그래프를 정상적으로 받는다")
    void 소속이_확인되면_내준다() {
        givenArtifacts();

        GraphResponse graph = service.build(1L, 7L);

        assertThat(graph.nodes()).isNotEmpty();
    }

    /* ══════════════════════════════════════
       코드(PR) → 명세
    ══════════════════════════════════════ */

    @Test
    @DisplayName("PR이 건드린 경로·테이블로 코드가 명세에 이어지고, 그 위 기능까지 영향이 올라간다")
    void PR을_명세에_연결한다() {
        givenArtifacts();
        givenPullRequest(7, "리뷰 작성 검증 보강", """
                {"apis":["/api/v1/reviews"],"tables":["reviews"],
                 "files":["src/main/java/.../ReviewController.java"]}""");

        GraphResponse graph = service.build(1L);

        String prId = "pr:100:7";
        assertThat(hasEdge(graph, prId, "api:GET:/api/v1/reviews", "CHANGES")).isTrue();
        assertThat(hasEdge(graph, prId, "api:POST:/api/v1/reviews", "CHANGES")).isTrue();
        assertThat(hasEdge(graph, prId, "table:reviews", "CHANGES")).isTrue();

        // 코드가 닿은 API를 거쳐 그것을 구현하는 기능까지 확인 대상이 된다.
        // "이 PR을 머지하면 어떤 요구사항이 걸리는가"에 대한 답이 여기서 나온다.
        assertThat(nodeById(graph, "feature:리뷰 작성").impacted()).isTrue();
        assertThat(nodeById(graph, "feature:검증 리뷰 목록 조회").impacted()).isTrue();

        // 손대지 않은 쪽은 조용해야 한다
        assertThat(nodeById(graph, "api:GET:/api/v1/banners").impacted()).isFalse();

        assertThat(graph.summary().prCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("코드에서 건진 경로가 명세보다 짧아도 같은 엔드포인트로 본다")
    void 부분_경로도_같은_엔드포인트로_본다() {
        givenArtifacts();
        // 컨트롤러의 @RequestMapping만 diff에 잡힌 상황 — 메서드 매핑은 안 바뀌었다
        givenPullRequest(8, "점수 계산 수정", """
                {"apis":["/api/v1/reviews"],"tables":[],"files":["ScoreService.java"]}""");

        GraphResponse graph = service.build(1L);

        assertThat(hasEdge(graph, "pr:100:8", "api:GET:/api/v1/reviews/{reviewId}/score", "CHANGES")).isTrue();
    }

    @Test
    @DisplayName("명세에 걸리는 접점이 없는 PR은 그래프에 올리지 않는다")
    void 접점이_없는_PR은_제외한다() {
        givenArtifacts();
        givenPullRequest(9, "README 오타 수정", """
                {"apis":[],"tables":[],"files":["README.md"]}""");

        GraphResponse graph = service.build(1L);

        assertThat(graph.summary().prCount()).isZero();
        assertThat(graph.nodes()).noneMatch(n -> "pr".equals(n.type()));
    }

    @Test
    @DisplayName("머지되거나 닫힌 PR은 그래프에서 내린다")
    void 닫힌_PR은_제외한다() {
        givenArtifacts();
        givenPullRequest(42, "리뷰 작성 검증 보강", """
                {"apis":["/api/v1/reviews"],"tables":["reviews"],"files":["ReviewController.java"]}""",
                "closed");

        GraphResponse graph = service.build(1L);

        // 그래프의 PR은 "지금 진행 중"이라는 뜻이다. 끝난 작업이 남으면
        // 몇 달 전 변경까지 영향 표시가 켜진 채로 쌓인다.
        assertThat(graph.summary().prCount()).isZero();
        assertThat(graph.nodes()).noneMatch(n -> "pr".equals(n.type()));
        // 그 PR이 켜 두었던 영향 표시도 함께 꺼져야 한다
        assertThat(nodeById(graph, "feature:리뷰 작성").impacted()).isFalse();
    }

    @Test
    @DisplayName("레포가 연결되지 않은 프로젝트는 PR 조회를 시도하지 않는다")
    void 레포가_없으면_PR도_없다() {
        givenArtifacts();
        given(projectRepoLinkRepository.findByProjectId(1L)).willReturn(List.of());

        GraphResponse graph = service.build(1L);

        assertThat(graph.summary().prCount()).isZero();
    }

    /* ── 검사 도우미 ── */

    private void givenPullRequest(int pullNumber, String title, String touchedJson) {
        givenPullRequest(pullNumber, title, touchedJson, "open");
    }

    /** 레포 하나가 연결돼 있고 그 레포에 검사를 마친 PR이 하나 있는 상황을 꾸민다 */
    private void givenPullRequest(int pullNumber, String title, String touchedJson, String state) {
        given(projectRepoLinkRepository.findByProjectId(1L)).willReturn(List.of(
                ProjectRepoLink.builder().projectId(1L).githubRepoId(100L).build()));

        given(reviewRecordRepository.findByProjectIdAndGithubRepoIdIn(1L, List.of(100L))).willReturn(List.of(
                GithubPullRequestReviewRecord.builder()
                        .projectId(1L)
                        .githubRepoId(100L)
                        .pullNumber(pullNumber)
                        .headSha("abc1234")
                        .pullTitle(title)
                        .pullUrl("https://github.com/team/repo/pull/" + pullNumber)
                        .pullState(state)
                        .touchedJson(touchedJson)
                        .score(75)
                        .evaluator("PYTHON_EXAONE")
                        .findingsJson("""
                                [{"severity":"WARNING","area":"API 명세","message":"확인 필요"}]""")
                        .build()));
    }


    /**
     * 해당 문서에만 직전 버전이 있었던 것으로 꾸민다.
     * 서비스는 문서 세 종류 모두의 이력을 찾아보므로 나머지는 "이력 없음"으로 명시해 둔다.
     */
    private void givenRevision(PipelineArtifact.ArtifactType type, String previousContent) {
        for (PipelineArtifact.ArtifactType t : List.of(
                PipelineArtifact.ArtifactType.FEATURE_LIST,
                PipelineArtifact.ArtifactType.API_SPEC,
                PipelineArtifact.ArtifactType.DB_SCHEMA)) {

            long artifactId = t.ordinal() + 1;
            given(revisionRepository.findFirstByArtifactIdOrderByVersionDesc(artifactId))
                    .willReturn(t == type
                            ? java.util.Optional.of(ArtifactRevision.builder()
                                    .artifactId(artifactId)
                                    .version(1)
                                    .content(previousContent)
                                    .build())
                            : java.util.Optional.empty());
        }
    }

    private boolean edgeExists(GraphResponse graph, String source, String target) {
        return graph.edges().stream()
                .anyMatch(e -> e.source().equals(source) && e.target().equals(target));
    }

    private boolean hasEdge(GraphResponse graph, String source, String target, String type) {
        return graph.edges().stream()
                .anyMatch(e -> e.source().equals(source) && e.target().equals(target) && e.type().equals(type));
    }

    private GraphResponse.Node nodeById(GraphResponse graph, String id) {
        return graph.nodes().stream()
                .filter(n -> n.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("노드를 찾을 수 없습니다: " + id));
    }
}
