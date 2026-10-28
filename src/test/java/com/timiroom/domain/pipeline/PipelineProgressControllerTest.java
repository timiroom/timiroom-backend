package com.timiroom.domain.pipeline;

import com.timiroom.domain.pipeline.controller.PipelineController;
import com.timiroom.domain.pipeline.service.PipelineService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PipelineProgressControllerTest {
    @Test
    void proxy_does_not_buffer_heartbeats_and_member_check_is_preserved() {
        var service = mock(PipelineService.class);
        var session = new MockHttpSession();
        session.setAttribute("memberId", 8L);
        when(service.subscribeProgress("run", 8L)).thenReturn(new SseEmitter());
        var response = new MockHttpServletResponse();
        new PipelineController(service, mock(com.timiroom.domain.spec.service.LegacyDocumentWriteService.class)).progress(session, "run", response);
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(response.getHeader("Cache-Control")).contains("no-transform");
        verify(service).subscribeProgress("run", 8L);
    }
}
