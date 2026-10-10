package com.rag.pipeline.phase1.form;

import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class FormToQueryService {

    /** 폼 전체 → 구조화 쿼리 문자열 */
    public String synthesize(FormData form) {
        StringBuilder sb = new StringBuilder();

        // 1. 프로젝트 기본 정보
        sb.append(String.format("%s 서비스를 %s 플랫폼으로 개발합니다.\n",
            form.projectName(), form.platform()));
        sb.append(form.projectDescription()).append("\n");
        if (form.techStack() != null && !form.techStack().isEmpty()) {
            sb.append("기술 스택: ").append(String.join(", ", form.techStack())).append("\n");
        }

        // 2. 문제 정의
        ProblemDefinition pd = form.problemDefinition();
        sb.append("\n[문제 정의]\n")
          .append("핵심 문제: ").append(pd.currentPainPoint()).append("\n")
          .append("현재 해결 방식: ").append(pd.currentSolution()).append("\n")
          .append("이상적인 상태: ").append(pd.idealState()).append("\n");
        if (notEmpty(pd.businessImpact()))
            sb.append("비즈니스 임팩트: ").append(pd.businessImpact()).append("\n");
        if (notEmpty(pd.motivation()))
            sb.append("서비스 동기: ").append(pd.motivation()).append("\n");
        if (notEmpty(pd.competitorGap()))
            sb.append("경쟁사 대비 차별점: ").append(pd.competitorGap()).append("\n");

        // 3. 타겟 유저
        sb.append("\n[타겟 유저]\n");
        for (int i = 0; i < form.targetUsers().size(); i++) {
            TargetUser u = form.targetUsers().get(i);
            sb.append(String.format("유저%d: %s / %s / %s\n",
                i + 1, u.persona(), u.usageEnvironment(), u.biggestPainPoint()));
        }

        // 4. 기능 목록 (MoSCoW)
        FeatureDefinition fd = form.featureDefinition();
        List<String> must   = getMust(fd);
        List<String> should = getByPriority(fd, MoSCoW.SHOULD);
        List<String> could  = getByPriority(fd, MoSCoW.COULD);
        List<String> wont   = getByPriority(fd, MoSCoW.WONT);

        sb.append("\n[기능 정의]\n");
        if (!must.isEmpty())   sb.append("MVP 필수(Must): ").append(String.join(", ", must)).append("\n");
        if (!should.isEmpty()) sb.append("우선순위 높음(Should): ").append(String.join(", ", should)).append("\n");
        if (!could.isEmpty())  sb.append("여유 시 포함(Could): ").append(String.join(", ", could)).append("\n");
        if (!wont.isEmpty())   sb.append("명시적 제외(Won't): ").append(String.join(", ", wont)).append("\n");

        return sb.toString().trim();
    }

    /** PmAgent mvpScope — commonFeatures(selected) + customFeatures(MUST) */
    public List<String> extractMustFeatures(FormData form) {
        return getMust(form.featureDefinition());
    }

    /** DbaAgent/ApiAgent 명시적 제외 목록 */
    public List<String> extractExcludedFeatures(FormData form) {
        return getByPriority(form.featureDefinition(), MoSCoW.WONT);
    }

    /** PmAgent featureList 전체 (Must + Should + Could) */
    public List<String> extractAllIncludedFeatures(FormData form) {
        List<String> all = new ArrayList<>(getMust(form.featureDefinition()));
        all.addAll(getByPriority(form.featureDefinition(), MoSCoW.SHOULD));
        all.addAll(getByPriority(form.featureDefinition(), MoSCoW.COULD));
        return all;
    }

    // ── helpers ──────────────────────────────────────────────────────
    private List<String> getMust(FeatureDefinition fd) {
        List<String> result = new ArrayList<>();
        if (fd.commonFeatures() != null)
            fd.commonFeatures().stream().filter(CommonFeature::selected)
              .map(CommonFeature::featureName).forEach(result::add);
        if (fd.customFeatures() != null)
            fd.customFeatures().stream().filter(f -> f.priority() == MoSCoW.MUST)
              .map(CustomFeature::featureName).forEach(result::add);
        return result;
    }

    private List<String> getByPriority(FeatureDefinition fd, MoSCoW priority) {
        if (fd.customFeatures() == null) return List.of();
        return fd.customFeatures().stream()
                 .filter(f -> f.priority() == priority)
                 .map(CustomFeature::featureName)
                 .collect(Collectors.toList());
    }

    private boolean notEmpty(String s) {
        return s != null && !s.isBlank();
    }
}
