package com.timiroom.infra.slack;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlackBrowserConnectionTest {
    final SlackOidcClient client=mock(SlackOidcClient.class);
    final SlackAccountService accounts=mock(SlackAccountService.class);
    final Instant now=Instant.parse("2026-10-10T00:00:00Z");
    final SlackBrowserConnection flow=new SlackBrowserConnection(client,accounts,Clock.fixed(now,ZoneOffset.UTC));
    final MockHttpSession session=new MockHttpSession();

    @Test void verifiedIdentityLinksOnceToTheInitiatingMember() {
        when(client.ready()).thenReturn(true);
        flow.start(7L,session);
        var pending=(SlackBrowserConnection.Pending)session.getAttribute(SlackBrowserConnection.KEY);
        when(client.identity("code",pending.nonce())).thenReturn(new SlackOidcClient.Identity("TTEST","UTEST"));
        assertThat(flow.complete(7L,session,pending.state(),"code",null)).isEqualTo("connected");
        verify(accounts).linkIdentity(7L,"TTEST","UTEST");
        assertThat(flow.complete(7L,session,pending.state(),"code",null)).isEqualTo("failed");
        verifyNoMoreInteractions(accounts);
    }
    @Test void differentBrowserMemberOrStateCannotLink() {
        when(client.ready()).thenReturn(true);flow.start(7L,session);
        var pending=(SlackBrowserConnection.Pending)session.getAttribute(SlackBrowserConnection.KEY);
        assertThat(flow.complete(7L,new MockHttpSession(),pending.state(),"code",null)).isEqualTo("failed");
        assertThat(flow.complete(8L,session,pending.state(),"code",null)).isEqualTo("failed");
        flow.start(7L,session);
        assertThat(flow.complete(7L,session,"wrong","code",null)).isEqualTo("failed");
        verify(client,never()).identity(any(),any());verifyNoInteractions(accounts);
    }
    @Test void expiredOrCancelledFlowNeverLinks() {
        session.setAttribute(SlackBrowserConnection.KEY,new SlackBrowserConnection.Pending(7L,"state","nonce",now.minusSeconds(1)));
        assertThat(flow.complete(7L,session,"state","code",null)).isEqualTo("failed");
        when(client.ready()).thenReturn(true);flow.start(7L,session);
        var pending=(SlackBrowserConnection.Pending)session.getAttribute(SlackBrowserConnection.KEY);
        assertThat(flow.complete(7L,session,pending.state(),null,"access_denied")).isEqualTo("cancelled");
        verifyNoInteractions(accounts);
    }
    @Test void staleOrForgedCallbackCannotCancelCurrentAttempt() {
        when(client.ready()).thenReturn(true);flow.start(7L,session);
        var pending=(SlackBrowserConnection.Pending)session.getAttribute(SlackBrowserConnection.KEY);
        assertThat(flow.complete(7L,session,"stale-state","wrong-code",null)).isEqualTo("failed");
        assertThat(session.getAttribute(SlackBrowserConnection.KEY)).isEqualTo(pending);
        when(client.identity("code",pending.nonce())).thenReturn(new SlackOidcClient.Identity("TTEST","UTEST"));
        assertThat(flow.complete(7L,session,pending.state(),"code",null)).isEqualTo("connected");
        verify(accounts).linkIdentity(7L,"TTEST","UTEST");
    }
    @Test void missingConfigurationDoesNotPretendToStart() {
        assertThatThrownBy(()->flow.start(7L,session)).isInstanceOf(IllegalStateException.class);
        assertThat(session.getAttribute(SlackBrowserConnection.KEY)).isNull();
    }
}
