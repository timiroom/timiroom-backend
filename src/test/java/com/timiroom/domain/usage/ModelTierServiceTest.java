package com.timiroom.domain.usage;

import com.timiroom.domain.usage.enums.PlanType;
import com.timiroom.domain.usage.service.ModelTierService;
import com.timiroom.domain.usage.service.ModelTierService.Tier;
import com.timiroom.domain.usage.service.UsageService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelTierServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UsageService usage = mock(UsageService.class);
    private final ModelTierService service = new ModelTierService(jdbc, usage);

    private void plan(PlanType plan) {
        UsageService.Credit credit = new UsageService.Credit(false, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        when(usage.statusOf(7L)).thenReturn(new UsageService.Status(plan, plan.name(), 1000, BigDecimal.ZERO, BigDecimal.ZERO,
                0, BigDecimal.ZERO, new UsageService.Period(Instant.now(), Instant.now()), credit));
    }

    private void stored(String tier) {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(7L))).thenReturn(tier == null ? List.of() : List.of(tier));
    }

    @Test
    void 고르지_않았으면_빠름이다() {
        plan(PlanType.FREE);
        stored(null);
        assertThat(service.effective(7L)).isEqualTo(Tier.LOW);
    }

    @Test
    void 무료는_정밀을_고를_수_없다() {
        plan(PlanType.FREE);
        assertThatThrownBy(() -> service.choose(7L, "high")).hasMessageContaining("프로");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void 프로가_끝나면_정밀은_균형으로_쓴다() {
        plan(PlanType.FREE);
        stored("HIGH");
        assertThat(service.effective(7L)).isEqualTo(Tier.MEDIUM);
    }

    @Test
    void 프로는_정밀을_저장하고_그대로_쓴다() {
        plan(PlanType.PRO);
        stored("HIGH");
        assertThat(service.choose(7L, "HIGH")).isEqualTo(Tier.HIGH);
        assertThat(service.effective(7L)).isEqualTo(Tier.HIGH);
    }

    @Test
    void 없는_품질은_막는다() {
        plan(PlanType.PRO);
        assertThatThrownBy(() -> service.choose(7L, "best")).isInstanceOf(IllegalArgumentException.class);
    }
}
