package com.timiroom.domain.usage;

import com.timiroom.domain.usage.enums.PlanType;
import com.timiroom.domain.usage.enums.UsageFeature;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 요금제 한도와 모델 단가. 모든 금액은 원 단위다.
 * 단가는 "100만 토큰당 원"으로 적고, 목록에 없는 모델은 default 단가를 쓴다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "usage")
public class UsageProperties {

    /** 꺼 두면 한도 검사를 하지 않는다(기록은 계속한다). */
    private boolean enforce = true;

    /** 사용량 기간·초기화 날짜를 계산하는 시간대 */
    private String zone = "Asia/Seoul";

    private Map<PlanType, Plan> plans = new EnumMap<>(Map.of(
            PlanType.FREE, new Plan("무료", 0, 3_000),
            PlanType.PRO, new Plan("프로", 9_900, 30_000)));

    private List<ModelPrice> models = new ArrayList<>();

    private ModelPrice defaultPrice = new ModelPrice("*", new BigDecimal("1500"), new BigDecimal("6000"));

    /** 기능을 시작하려면 남아 있어야 하는 최소 금액. 프로젝트 생성처럼 큰 작업이 중간에 한도를 크게 넘지 않게 한다. */
    private Map<UsageFeature, BigDecimal> reserveKrw = new EnumMap<>(Map.of(
            UsageFeature.PROJECT_GENERATION, new BigDecimal("300")));

    /** reserveKrw에 없는 기능의 기본값. 진행 중인 호출 하나가 남은 양에서 미리 잡아 두는 금액이기도 하다. */
    private BigDecimal defaultReserveKrw = new BigDecimal("20");

    /** 진행 중인 호출의 예약을 이 시간이 지나면 무시한다(기록하지 못하고 끝난 호출 정리). */
    private java.time.Duration holdTtl = java.time.Duration.ofMinutes(30);

    /** 운영 비용 경보: 하루 전체 AI 비용, 한 사람의 하루 AI 비용(원) */
    private long alertDailyTotalKrw = 50_000;
    private long alertDailyMemberKrw = 5_000;

    public BigDecimal reserveOf(UsageFeature feature) {
        return reserveKrw.getOrDefault(feature, defaultReserveKrw);
    }

    /**
     * 추가 사용량(충전 금액). 요금제 한도를 다 쓰면 실제 쓴 만큼 원 단위로 잔액에서 차감한다.
     * creditRate: 내부 비용 1원당 잔액에서 빼는 금액(마진). 1이면 비용 그대로 차감한다.
     */
    private BigDecimal creditRate = BigDecimal.ONE;
    private List<Long> creditPresetsKrw = new ArrayList<>(List.of(5_000L, 10_000L, 30_000L));
    private long creditMinKrw = 5_000;
    private long creditMaxKrw = 500_000;

    @Getter
    @Setter
    public static class Plan {
        private String name;
        private long priceKrw;
        private long monthlyLimitKrw;

        public Plan() {}

        public Plan(String name, long priceKrw, long monthlyLimitKrw) {
            this.name = name;
            this.priceKrw = priceKrw;
            this.monthlyLimitKrw = monthlyLimitKrw;
        }
    }

    @Getter
    @Setter
    public static class ModelPrice {
        /** 모델 이름. 끝이 *이면 앞부분이 같은 모델 모두에 쓴다(예: gpt-5.4*). */
        private String model;
        private BigDecimal inputKrwPerMillion = BigDecimal.ZERO;
        private BigDecimal outputKrwPerMillion = BigDecimal.ZERO;

        public ModelPrice() {}

        public ModelPrice(String model, BigDecimal input, BigDecimal output) {
            this.model = model;
            this.inputKrwPerMillion = input;
            this.outputKrwPerMillion = output;
        }

        boolean matches(String name) {
            if (model == null || name == null) return false;
            return model.endsWith("*") ? name.startsWith(model.substring(0, model.length() - 1)) : model.equals(name);
        }
    }

    public Plan plan(PlanType type) {
        return plans.getOrDefault(type, plans.get(PlanType.FREE));
    }

    public ModelPrice priceOf(String model) {
        return models.stream().filter(p -> p.matches(model)).findFirst().orElse(defaultPrice);
    }
}
