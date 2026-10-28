package com.rag.pipeline.common.skills;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 앱 시작 시 classpath(pm-skills/*.md)에서 PM 스킬을 로드하고 임베딩하여 메모리에 보관.
 * 파이프라인 실행 시 쿼리와 유사한 상위 N개 스킬만 PmAgent 프롬프트에 주입한다.
 */
@Slf4j
@Component
public class PmSkillsLoader {

    private static final String SKILLS_PATTERN = "classpath:pm-skills/*.md";

    private final EmbeddingModel embeddingModel;
    private final List<SkillEntry> skills = new ArrayList<>();

    public PmSkillsLoader(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @PostConstruct
    public void loadSkills() {
        log.info("PM 스킬 로드 시작 — {}", SKILLS_PATTERN);

        List<RawSkill> rawSkills = loadFromClasspath();
        if (rawSkills.isEmpty()) {
            log.warn("PM 스킬을 하나도 로드하지 못했습니다. (classpath:pm-skills/ 폴더를 확인하세요)");
            return;
        }
        log.info("PM 스킬 파일 로드 완료 — {}개", rawSkills.size());

        embedSkills(rawSkills);
        log.info("PM 스킬 임베딩 완료 — {}개 메모리에 저장", skills.size());
    }

    /**
     * 쿼리와 코사인 유사도가 높은 상위 topK개의 스킬 내용을 반환한다.
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

        log.info("PM 스킬 RAG 선택 결과 (상위 {}/{})", ranked.size(), skills.size());
        for (int i = 0; i < ranked.size(); i++) {
            ScoredSkill ss = ranked.get(i);
            log.info("  {}위. {} (유사도: {})", i + 1, ss.entry().slug(),
                    String.format("%.4f", ss.score()));
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

    // ── private ────────────────────────────────────────────────────

    private List<RawSkill> loadFromClasspath() {
        List<RawSkill> result = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(SKILLS_PATTERN);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".md")) continue;
                String slug = filename.replace(".md", "");
                try (InputStream is = resource.getInputStream()) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (!content.isBlank()) {
                        result.add(new RawSkill(slug, content));
                    }
                } catch (IOException e) {
                    log.warn("PM 스킬 파일 읽기 실패: {} — {}", filename, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("PM 스킬 classpath 스캔 실패: {}", e.getMessage());
        }
        return result;
    }

    private void embedSkills(List<RawSkill> rawSkills) {
        for (RawSkill raw : rawSkills) {
            try {
                float[] vec = embeddingModel.embed(raw.content());
                skills.add(new SkillEntry(raw.slug(), raw.content(), vec));
            } catch (Exception e) {
                log.warn("PM 스킬 임베딩 실패: {} — {}", raw.slug(), e.getMessage());
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
