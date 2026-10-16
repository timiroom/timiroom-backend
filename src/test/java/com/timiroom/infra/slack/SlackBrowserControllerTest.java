package com.timiroom.infra.slack;

import com.timiroom.domain.integration.service.IntegrationActorResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SlackBrowserControllerTest {
    final SlackBrowserConnection flow=mock(SlackBrowserConnection.class);
    final SlackBrowserController controller=new SlackBrowserController(flow,mock(IntegrationActorResolver.class));
    @Test void publicRelayDoesNotCreateSessionOrLinkAccount() throws Exception {
        ReflectionTestUtils.setField(controller,"frontend","https://timiroom.kro.kr");
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/integrations/slack/oauth/callback").contentType("application/x-www-form-urlencoded")
            .param("state","a".repeat(43)).param("code","value&error=forged"))
            .andExpect(status().isSeeOther()).andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(header().string("Referrer-Policy","no-referrer"))
            .andExpect(redirectedUrl("/integrations/slack/oauth/complete?state="+"a".repeat(43)+"&code=value%26error%3Dforged"));
        verifyNoInteractions(flow);
    }
    @Test void malformedCallbackReturnsToFixedFrontend() throws Exception {
        ReflectionTestUtils.setField(controller,"frontend","https://timiroom.kro.kr");
        MockMvcBuilders.standaloneSetup(controller).build().perform(post("/integrations/slack/oauth/callback")
            .contentType("application/x-www-form-urlencoded").param("state","https://evil.example"))
            .andExpect(redirectedUrl("https://timiroom.kro.kr/mypage?slack=failed"));
        verifyNoInteractions(flow);
    }
}
