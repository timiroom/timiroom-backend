package com.rag.pipeline.common.skills;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 앱 시작 시 GitHub에서 PM 스킬 파일을 가져와 임베딩한 뒤 메모리에 보관.
 * 파이프라인 실행 시 쿼리와 유사한 상위 N개 스킬만 PmAgent 프롬프트에 주입한다.
 *
 * pgvector에 섞지 않으므로 기존 RAG 지식베이스와 완전히 분리된다.
 */
@Slf4j
@Component
public class PmSkillsLoader {

    private static final String RAW_BASE =
            "https://raw.githubusercontent.com/product-on-purpose/pm-skills/main/skills/%s/SKILL.md";

    private static final List<String> SKILL_SLUGS = Arrays.asList(
            "define-hypothesis",
            "define-jtbd-canvas",
            "define-opportunity-tree",
            "define-prioritization-framework",
            "define-problem-statement",
            "deliver-acceptance-criteria",
            "deliver-edge-cases",
            "deliver-launch-checklist",
            "deliver-prd",
            "deliver-release-notes",
            "deliver-user-stories",
            "develop-adr",
            "develop-design-rationale",
            "develop-solution-brief",
            "develop-spike-summary",
            "discover-competitive-analysis",
            "discover-interview-synthesis",
            "discover-journey-map",
            "discover-market-sizing",
            "discover-stakeholder-summary",
            "foundation-lean-canvas",
            "foundation-meeting-agenda",
            "foundation-meeting-brief",
            "foundation-meeting-recap",
            "foundation-meeting-synthesize",
            "foundation-okr-writer",
            "foundation-persona",
            "foundation-stakeholder-update",
            "iterate-lessons-log",
            "iterate-pivot-decision",
            "iterate-refinement-notes",
            "iterate-retrospective",
            "measure-dashboard-requirements",
            "measure-experiment-design",
            "measure-experiment-results",
            "measure-instrumentation-spec",
            "measure-okr-grader",
            "measure-survey-analysis",
            "tool-design-sprint-brief",
            "tool-design-sprint-decide-and-storyboard",
            "tool-design-sprint-map-and-target",
            "tool-design-sprint-prototype-plan",
            "tool-design-sprint-readiness",
            "tool-design-sprint-sketch",
            "tool-design-sprint-test-and-score",
            "tool-foundation-sprint-approach-options",
            "tool-foundation-sprint-basics",
            "tool-foundation-sprint-brief",
            "tool-foundation-sprint-differentiation",
            "tool-foundation-sprint-founding-hypothesis",
            "tool-foundation-sprint-magic-lenses",
            "tool-foundation-sprint-readiness",
            "tool-note-and-vote",
            "utility-mermaid-diagrams",
            "utility-pm-changelog-curator",
            "utility-pm-critic",
            "utility-pm-release-conductor",
            "utility-pm-skill-auditor",
            "utility-pm-skill-builder",
            "utility-pm-skill-iterate",
            "utility-pm-skill-validate",
            "utility-slideshow-creator",
            "utility-update-pm-skills"
    );

    private final RestClient restClient;
    private final EmbeddingModel embeddingModel;

    private final List<SkillEntry> skills = new ArrayList<>();

    public PmSkillsLoader(RestClient.Builder restClientBuilder, EmbeddingModel embeddingModel) {
        this.restClient = restClientBuilder.build();
        this.embeddingModel = embeddingModel;
    }

    @PostConstruct
    public void loadSkills() {
        log.info("PM 스킬 로드 시작 — 총 {} 개 병렬 fetch", SKILL_SLUGS.size());

        // 1. GitHub에서 병렬 fetch
        List<RawSkill> rawSkills = fetchAllSkills();
        if (rawSkills.isEmpty()) {
            log.warn("PM 스킬을 하나도 로드하지 못했습니다.");
            return;
        }
        log.info("PM 스킬 fetch 완료 — {}/{} 개 성공", rawSkills.size(), SKILL_SLUGS.size());

        // 2. 임베딩 (배치 처리, 10개씩)
        embedSkills(rawSkills);
        log.info("PM 스킬 임베딩 완료 — {} 개 메모리에 저장", skills.size());
    }

    /**
     * 주어진 쿼리와 코사인 유사도가 높은 상위 topK개의 스킬 내용을 반환한다.
     */
    public String findRelevantSkills(String query, int topK) {
        if (skills.isEmpty()) return "";

        float[] queryVec = embeddingModel.embed(query);

        record ScoredSkill(SkillEntry entry, double score) {}

        List<ScoredSkill> ranked = skills.stream()
                .map(s -> new ScoredSkill(s, cosineSimilarity(queryVec, s.embedding())))
                .sorted(Comparator.comparingDouble(ScoredSkill::score).reversed())
                .limit(topK)
                .toList();

        log.info("━━━ PM 스킬 RAG 선택 결과 (상위 {}/{}) ━━━", ranked.size(), skills.size());
        for (int i = 0; i < ranked.size(); i++) {
            ScoredSkill ss = ranked.get(i);
            log.info("  {}위. {} (유사도: {:.4f})", i + 1, ss.entry().slug(), ss.score());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n## 관련 PM 방법론 (적용 필수)\n");
        sb.append("아래는 이 프로젝트에 가장 관련된 PM 프레임워크입니다. 산출물 생성 시 실제로 적용하세요.\n\n");
        for (ScoredSkill ss : ranked) {
            sb.append("### ").append(ss.entry().slug()).append("\n");
            sb.append(ss.entry().content()).append("\n\n");
        }
        return sb.toString();
    }

    public boolean hasSkills() {
        return !skills.isEmpty();
    }

    // ── 내부 구현 ──────────────────────────────────────────────────

    private List<RawSkill> fetchAllSkills() {
        List<RawSkill> result = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(10);
        try {
            List<Future<RawSkill>> futures = SKILL_SLUGS.stream()
                    .map(slug -> executor.submit(() -> {
                        try {
                            String content = restClient.get()
                                    .uri(String.format(RAW_BASE, slug))
                                    .retrieve()
                                    .body(String.class);
                            if (content != null && !content.isBlank()) {
                                return new RawSkill(slug, content.trim());
                            }
                        } catch (Exception e) {
                            log.warn("PM 스킬 fetch 실패: {} — {}", slug, e.getMessage());
                        }
                        return null;
                    }))
                    .toList();

            for (Future<RawSkill> f : futures) {
                try {
                    RawSkill raw = f.get();
                    if (raw != null) result.add(raw);
                } catch (Exception ignored) {}
            }
        } finally {
            executor.shutdown();
        }
        return result;
    }

    private void embedSkills(List<RawSkill> rawSkills) {
        int batchSize = 10;
        for (int i = 0; i < rawSkills.size(); i += batchSize) {
            List<RawSkill> batch = rawSkills.subList(i, Math.min(i + batchSize, rawSkills.size()));
            for (RawSkill raw : batch) {
                try {
                    float[] vec = embeddingModel.embed(raw.content());
                    skills.add(new SkillEntry(raw.slug(), raw.content(), vec));
                } catch (Exception e) {
                    log.warn("PM 스킬 임베딩 실패: {} — {}", raw.slug(), e.getMessage());
                }
            }
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    private record RawSkill(String slug, String content) {}
    private record SkillEntry(String slug, String content, float[] embedding) {}
}
