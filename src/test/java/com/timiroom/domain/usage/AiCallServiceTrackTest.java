package com.timiroom.domain.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.aikey.service.LlmCredentialService;
import com.timiroom.domain.usage.enums.UsageFeature;
import com.timiroom.domain.usage.service.AiCallService;
import com.timiroom.domain.usage.service.AiUsageRow;
import com.timiroom.domain.usage.service.AiUsageScope;
import com.timiroom.domain.usage.service.UsageLimitExceededException;
import com.timiroom.domain.usage.service.UsageService;
import com.timiroom.domain.usage.service.UsageHoldService;
import com.timiroom.domain.billing.service.BillingNotifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AiCallServiceTrackTest {

    private final UsageService usage = mock(UsageService.class);
    private final UsageHoldService holds = mock(UsageHoldService.class);
    private final AiCallService service = new AiCallService(usage, mock(LlmCredentialService.class), new ObjectMapper(),
            holds, mock(com.timiroom.domain.usage.service.ModelTierService.class), mock(BillingNotifier.class));

    private static String header(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsUsageCollectedFromHeadersAndBodiesEvenWhenWorkFails() throws Exception {
        var body = new ObjectMapper().readTree("[{\"model\":\"m2\",\"inputTokens\":5,\"outputTokens\":1}]");

        assertThatThrownBy(() -> service.track(7L, 3L, UsageFeature.PR_CONSISTENCY, "pr:1#2", () -> {
            AiUsageScope.addHeader(header("[{\"model\":\"m1\",\"inputTokens\":10,\"outputTokens\":2}]"));
            // 안쪽 track은 바깥 범위에 합쳐지고 따로 기록하지 않는다.
            service.track(7L, 3L, UsageFeature.PR_CONSISTENCY, "inner", () -> {
                AiUsageScope.addNode(body);
                return null;
            });
            throw new IllegalStateException("GitHub 실패");
        })).isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<List<AiUsageRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(usage).record(eq(7L), eq(3L), eq(UsageFeature.PR_CONSISTENCY), rows.capture(), eq("pr:1#2"), eq(true));
        assertThat(rows.getValue()).extracting(AiUsageRow::model).containsExactly("m1", "m2");
        assertThat(AiUsageScope.active()).isFalse();
    }

    @Test
    void doesNotStartWorkWhenLimitIsReached() {
        doThrow(new UsageLimitExceededException(Instant.now(), "FREE"))
                .when(holds).reserve(eq(7L), any(), eq(false));

        assertThatThrownBy(() -> service.track(7L, 3L, UsageFeature.SPEC_REVIEW, "job:1", () -> {
            throw new AssertionError("실행되면 안 됩니다");
        })).isInstanceOf(UsageLimitExceededException.class);

        verify(usage, never()).record(anyLong(), any(), any(), anyList(), anyString(), anyBoolean());
        assertThat(AiUsageScope.active()).isFalse();
    }
}
