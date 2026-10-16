package com.rag.pipeline.phase1;

import com.rag.pipeline.common.dto.DocumentChunk;
import com.rag.pipeline.phase1.form.FormData;
import com.rag.pipeline.phase1.form.FormToQueryService;
import com.rag.pipeline.phase1.form.ProblemDefinition;
import com.rag.pipeline.phase1.form.TargetUser;
import com.rag.pipeline.phase1.parsing.PDFParsingService;
import com.rag.pipeline.phase1.reranker.RerankerService;
import com.rag.pipeline.phase1.rl.SearchRLService;
import com.rag.pipeline.phase1.search.HybridSearchService;
import com.rag.pipeline.phase1.search.QueryExpansionService;
import com.rag.pipeline.phase1.session.SessionVectorStore;
import com.rag.pipeline.phase2.state.PipelineState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 1 — 전체 RAG 파이프라인 오케스트레이터
 *
 * STEP 1. 채팅 입력 수신
 * STEP 2. Query Expansion    (GPT-4o-mini)
 * STEP 3. Hybrid Search      (pgvector + PostgreSQL FTS + RRF)
 * STEP 4. Reranker           (Cohere Rerank API)
 * STEP 5. Semantic Context   조립 → Phase 2 입력 프롬프트 완성
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagPipelineService {

    private final QueryExpansionService queryExpansionService;
    private final HybridSearchService   hybridSearchService;
    private final RerankerService       rerankerService;
    private final SearchRLService       searchRLService;
    private final FormToQueryService    formToQueryService;
    private final PDFParsingService     pdfParsingService;
    private final SessionVectorStore    sessionVectorStore;

    @Value("${app.rag.top-k-vector:20}")
    private int topKHybrid;

    private static final String CONTEXT_TEMPLATE = """
            당신은 소프트웨어 아키텍트입니다.
            아래 참조 문서와 사용자 요청을 바탕으로 분석하세요.
            
            [참조 문서]
            %s
            
            [사용자 요청]
            %s
            
            참조 문서를 최대한 활용하여 요청을 분석하고
            필요한 기능과 설계 방향을 도출하세요.
            """;

    /**
     * Phase 1 전체 실행
     * 사용자 쿼리 → 컨텍스트가 풍부한 프롬프트 반환
     *
     * @param userQuery 사용자 원본 입력
     * @return Phase 2 PM 에이전트에 전달할 완성된 프롬프트
     */
    public String buildContext(String userQuery) {
        log.info("=== Phase 1 시작 === query: '{}'", userQuery);

        // STEP 2 — Query Expansion
        List<String> expandedQueries = queryExpansionService.expand(userQuery);
        log.info("STEP 2 완료 — {} queries 생성", expandedQueries.size());

        // STEP 3 — Hybrid Search
        List<DocumentChunk> hybridResults = hybridSearchService
                .searchMultiple(expandedQueries, topKHybrid);
        log.info("STEP 3 완료 — {} candidates 검색", hybridResults.size());

        // STEP 4 — Reranker
        List<DocumentChunk> rerankedChunks = rerankerService
                .rerank(userQuery, hybridResults);
        log.info("STEP 4 완료 — {} chunks 최종 선택", rerankedChunks.size());

        // STEP 5 — Context 조립
        String context = buildContextString(rerankedChunks);
        String finalPrompt = String.format(CONTEXT_TEMPLATE, context, userQuery);

        log.info("=== Phase 1 완료 === context 길이: {} chars", context.length());
        return finalPrompt;
    }

    /**
     * [임시] 폼 데이터 + PDF 직접 수신 → Phase 1 전체 실행
     *
     * 처리 순서:
     * 1. PDF 파싱 → SessionVectorStore
     * 2. 폼 → 구조화 쿼리 합성
     * 3. 쿼리 확장 (6개)
     * 4. HybridSearch (글로벌 + 세션 병합)
     * 5. Reranking (최종 5개)
     * 6. PipelineState 조립 → 반환
     */
    public PipelineState buildFromForm(FormData formData, List<MultipartFile> pdfFiles) {
        String sessionId = UUID.randomUUID().toString();
        log.info("[{}] Phase 1 시작 — 프로젝트: {}", sessionId, formData.projectName());

        try {
            // Step 1: PDF 파싱
            pdfParsingService.parseAndStoreAll(pdfFiles, sessionId);

            // Step 2: 폼 → 구조화 쿼리
            String synthesizedQuery = formToQueryService.synthesize(formData);
            List<String> mustFeatures = formToQueryService.extractMustFeatures(formData);

            // Step 3: 폼 데이터 섹션에서 검색 쿼리 직접 추출 (GPT 호출 없음)
            List<String> queries = buildQueriesFromForm(formData, synthesizedQuery, mustFeatures);
            log.info("STEP 3 완료 — {} queries 추출 (폼 데이터 직접)", queries.size());

            // Step 4: Hybrid Search (글로벌 + 세션)
            List<DocumentChunk> retrieved =
                hybridSearchService.searchWithSession(queries, sessionId);

            // Step 5: Reranking
            List<DocumentChunk> reranked = rerankerService.rerank(synthesizedQuery, retrieved);

            // Step 5-1: Cohere 점수 평균 → Phase1 RL 피드백
            double avgCohereScore = reranked.stream()
                    .mapToDouble(c -> c.getRelevanceScore() != null ? c.getRelevanceScore() : 0.0)
                    .average()
                    .orElse(0.0);
            searchRLService.applyRerankScore(sessionId, avgCohereScore);
            log.info("Phase1 RL 피드백 적용 — avgCohereScore:{}", String.format("%.3f", avgCohereScore));

            // Step 6: PipelineState 조립
            String contextPrompt = assembleContext(reranked, synthesizedQuery);

            return PipelineState.builder()
                .sessionId(sessionId)
                .userQuery(synthesizedQuery)
                .projectName(formData.projectName())
                .platform(formData.platform())
                .techStack(formData.techStack())
                .problemDefinition(formData.problemDefinition())
                .targetUsers(formData.targetUsers())
                .mustFeatures(mustFeatures)
                .excludedFeatures(formToQueryService.extractExcludedFeatures(formData))
                .featureList(formToQueryService.extractAllIncludedFeatures(formData))
                .contextPrompt(contextPrompt)
                .statusMessage("Phase 1 완료")
                .build();

        } finally {
            sessionVectorStore.clear(sessionId);
        }
    }

    /**
     * 폼 데이터 섹션을 검색 쿼리 목록으로 변환 — GPT 호출 없음
     *
     * 1. 합성 쿼리  — 전체 맥락
     * 2. 서비스 개요 — 프로젝트명 + 설명
     * 3. 문제 정의  — 핵심 페인포인트 + 이상적 상태
     * 4. 타겟 유저  — 페르소나 + 불편함
     * 5. 기능 목록  — Must 기능 키워드
     */
    private List<String> buildQueriesFromForm(FormData formData,
                                              String synthesizedQuery,
                                              List<String> mustFeatures) {
        List<String> queries = new ArrayList<>();

        // 1. 전체 합성 쿼리
        queries.add(synthesizedQuery);

        // 2. 서비스 개요
        queries.add(formData.projectName() + " " + formData.projectDescription());

        // 3. 문제 정의
        ProblemDefinition pd = formData.problemDefinition();
        String problemQuery = pd.currentPainPoint() + " " + pd.idealState();
        if (pd.competitorGap() != null && !pd.competitorGap().isBlank()) {
            problemQuery += " " + pd.competitorGap();
        }
        queries.add(problemQuery);

        // 4. 타겟 유저
        if (formData.targetUsers() != null && !formData.targetUsers().isEmpty()) {
            String targetQuery = formData.targetUsers().stream()
                    .map(u -> u.persona() + " " + u.biggestPainPoint())
                    .collect(Collectors.joining(" "));
            queries.add(targetQuery);
        }

        // 5. 기능 목록 (null 값 필터링 후 추가)
        List<String> validFeatures = mustFeatures.stream()
                .filter(f -> f != null && !f.isBlank())
                .collect(Collectors.toList());
        if (!validFeatures.isEmpty()) {
            queries.add(String.join(" ", validFeatures));
        }

        log.debug("폼 쿼리 추출 — {}개: {}", queries.size(), queries);
        return queries;
    }

    private String assembleContext(List<DocumentChunk> chunks, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("[프로젝트 컨텍스트]\n").append(query).append("\n\n");
        if (!chunks.isEmpty()) {
            sb.append("[관련 지식베이스 — 상위 ").append(chunks.size()).append("개]\n");
            chunks.forEach(c -> sb.append(c.getContent()).append("\n---\n"));
        }
        return sb.toString();
    }

    /**
     * 청크 목록을 하나의 컨텍스트 문자열로 조립
     */
    private String buildContextString(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) {
            return "관련 참조 문서 없음";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            double score = chunk.getRelevanceScore() != null
                    ? chunk.getRelevanceScore() : 0.0;

            sb.append(String.format("--- 문서 %d (관련도: %.3f) ---\n", i + 1, score));
            sb.append(chunk.getContent());
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }
}