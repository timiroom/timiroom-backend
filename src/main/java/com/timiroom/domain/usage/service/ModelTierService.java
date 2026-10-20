package com.timiroom.domain.usage.service;

import com.timiroom.domain.usage.enums.PlanType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 회원이 고르는 AI 품질. 빠름(LOW)·균형(MEDIUM)·정밀(HIGH)마다 AI 서버가 다른 모델을 쓰고,
 * 모델 단가가 달라 같은 일에도 사용량이 다르게 든다(화면에는 배수를 보여 주지 않는다). 정밀은 프로 요금제에서만 쓸 수 있다.
 * 사용자 AI 키가 있으면 그 키의 모델을 쓰므로 품질 선택은 적용하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ModelTierService {

    public enum Tier {
        LOW("빠름", "가장 빠르고 사용량이 적게 듭니다", false),
        MEDIUM("균형", "속도와 결과 품질의 균형을 맞춥니다", false),
        HIGH("정밀", "가장 자세한 결과를 만들지만 느리고 사용량이 많이 듭니다", true);

        public final String label;
        public final String description;
        public final boolean proOnly;

        Tier(String label, String description, boolean proOnly) {
            this.label = label;
            this.description = description;
            this.proOnly = proOnly;
        }
    }

    /** 고르지 않은 사용자는 균형으로 쓴다. 빠름은 생성 문서가 기능 계약을 빠뜨리는 일이 잦아 직접 골랐을 때만 쓴다. */
    public static final Tier DEFAULT = Tier.MEDIUM;

    private final JdbcTemplate jdbc;
    private final UsageService usageService;

    /** 저장된 값(없으면 기본값) */
    public Tier stored(Long memberId) {
        if (memberId == null) return DEFAULT;
        List<String> rows = jdbc.queryForList("select model_tier from member_ai_preference where member_id = ?", String.class, memberId);
        if (rows.isEmpty()) return DEFAULT;
        try {
            return Tier.valueOf(rows.get(0));
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }

    /** 지금 실제로 쓸 품질. 정밀을 골랐어도 프로가 끝났으면 균형으로 쓴다. */
    public Tier effective(Long memberId) {
        Tier tier = stored(memberId);
        if (tier.proOnly && !isPro(memberId)) return Tier.MEDIUM;
        return tier;
    }

    public Tier choose(Long memberId, String value) {
        Tier tier;
        try {
            tier = Tier.valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("AI 품질은 빠름, 균형, 정밀 중에서 고를 수 있습니다");
        }
        if (tier.proOnly && !isPro(memberId)) throw new IllegalArgumentException("정밀은 프로 요금제에서 쓸 수 있습니다");
        jdbc.update("insert into member_ai_preference(member_id, model_tier, updated_at) values (?, ?, ?) "
                + "on conflict (member_id) do update set model_tier = excluded.model_tier, updated_at = excluded.updated_at",
                memberId, tier.name(), Timestamp.from(Instant.now()));
        return tier;
    }

    public Map<String, Object> view(Long memberId) {
        boolean pro = isPro(memberId);
        Tier stored = stored(memberId);
        List<Map<String, Object>> options = new ArrayList<>();
        for (Tier t : Tier.values()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("tier", t.name());
            o.put("label", t.label);
            o.put("description", t.description);
            o.put("proOnly", t.proOnly);
            o.put("available", !t.proOnly || pro);
            options.add(o);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("tier", stored.name());
        view.put("effectiveTier", effective(memberId).name());
        view.put("options", options);
        return view;
    }

    private boolean isPro(Long memberId) {
        return usageService.statusOf(memberId).plan() == PlanType.PRO;
    }
}
