package com.timiroom.domain.integrationjob;

import com.timiroom.domain.integrationjob.service.IntegrationJobWorkerAccess;
import com.timiroom.infra.ragpipeline.AiServiceException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiJobFailureTest {
    @Test void aiReasonIsKeptOnTheJobEvenWhenWrapped() {
        var ai=new AiServiceException(400,"AI_KEY_INVALID","연결한 AI 키가 올바르지 않습니다.");
        assertThat(IntegrationJobWorkerAccess.aiFailure(new IllegalStateException("wrapper",ai)).code()).isEqualTo("AI_KEY_INVALID");
        assertThat(IntegrationJobWorkerAccess.aiFailure(new IllegalStateException("SPEC_CONFLICT"))).isNull();
        // AI_ 형식이 아닌 코드는 작업 기록에 쓰지 않는다
        assertThat(IntegrationJobWorkerAccess.aiFailure(new AiServiceException(503,"weird code","x"))).isNull();
    }
}
