package com.timiroom.infra.slack;

import com.slack.api.socket_mode.SocketModeClient;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlackSocketConnectionTest {
    @Test void startupOutageDoesNotFailTheWebServerAndTheNextAttemptConnects() throws Exception {
        var factory=mock(SlackSocketConnection.Factory.class);
        var client=mock(SocketModeClient.class);
        when(factory.open()).thenThrow(new java.io.IOException("secret URL must not be logged")).thenReturn(client);
        var timer=mock(ScheduledExecutorService.class);
        var workers=mock(ExecutorService.class);
        var handler=mock(SlackSocketHandler.class);
        var connection=new SlackSocketConnection("A1",factory,handler,timer,workers);
        connection.start();
        assertThatCode(connection::maintain).doesNotThrowAnyException();
        connection.maintain();
        verify(client).connect();
        connection.stop();
        verify(client).close();verify(timer).shutdownNow();verify(workers).shutdown();
    }
    @Test void socketAppIdentityMustBeVerifiedBeforeAnySlashCommand() throws Exception {
        var client=mock(SocketModeClient.class);
        var handler=mock(SlackSocketHandler.class);
        var connection=new SlackSocketConnection("A1",()->client,handler,mock(ScheduledExecutorService.class),mock(ExecutorService.class));
        connection.start();connection.maintain();
        var listener=org.mockito.ArgumentCaptor.forClass(com.slack.api.socket_mode.listener.WebSocketMessageListener.class);
        verify(client).addWebSocketMessageListener(listener.capture());
        listener.getValue().handle("{\"type\":\"hello\",\"connection_info\":{\"app_id\":\"A2\"}}");
        verify(client).setAutoReconnectEnabled(false);verify(client).disconnect();
        verifyNoInteractions(handler);
        connection.stop();
    }
    @Test void validHelloAndSlashPayloadAreForwardedWithAnEnvelopeAck() throws Exception {
        var client=mock(SocketModeClient.class);var handler=mock(SlackSocketHandler.class);
        var connection=new SlackSocketConnection("A1",()->client,handler,mock(ScheduledExecutorService.class),mock(ExecutorService.class));
        connection.start();connection.maintain();
        var hello=org.mockito.ArgumentCaptor.forClass(com.slack.api.socket_mode.listener.WebSocketMessageListener.class);
        verify(client).addWebSocketMessageListener(hello.capture());
        hello.getValue().handle("{\"type\":\"hello\",\"connection_info\":{\"app_id\":\"A1\"}}");
        var slash=org.mockito.ArgumentCaptor.forClass(com.slack.api.socket_mode.listener.EnvelopeListener.class);
        verify(client).addSlashCommandsEnvelopeListener(slash.capture());
        var envelope=new com.slack.api.socket_mode.request.SlashCommandsEnvelope();
        envelope.setEnvelopeId("e1");envelope.setPayload(com.google.gson.JsonParser.parseString("{\"team_id\":\"T1\",\"text\":\"help\"}"));
        slash.getValue().handle(envelope);
        verify(handler).receive(eq("e1"),eq(java.util.Map.of("team_id","T1","text","help")),any());
        connection.stop();
    }
}
