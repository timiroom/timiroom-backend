package com.timiroom.infra.slack;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlackSocketHandlerTest {
    private final SlackCommandService commands=mock(SlackCommandService.class);
    private final SlackWebClient web=mock(SlackWebClient.class);
    private Map<String,String> payload(String text) {
        return new HashMap<>(Map.of("team_id","T1","user_id","U1","channel_id","C1","command","/timiroom","text",text));
    }
    @Test void helpIsAcknowledgedWithoutDatabaseOrBotRoundTrip() {
        var acks=new ArrayList<Map<String,Object>>();
        var handler=new SlackSocketHandler("T1","A1",commands,web,Runnable::run);
        handler.receive("envelope-1",payload("help"),acks::add);
        assertThat(acks).singleElement().satisfies(ack->{
            assertThat(ack.get("envelope_id")).isEqualTo("envelope-1");
            assertThat(((Map<?,?>)ack.get("payload")).get("text")).isEqualTo(SlackCommandService.HELP);
        });
        verifyNoInteractions(commands,web);
    }
    @Test void ackPrecedesSlowDatabaseWorkAndUsesStableDeduplicationKey() {
        var pending=new ArrayList<Runnable>();
        var acks=new ArrayList<Map<String,Object>>();
        var handler=new SlackSocketHandler("T1","A1",commands,web,pending::add);
        when(commands.accept(any(),anyString())).thenAnswer(invocation->{
            assertThat(acks).hasSize(1);
            return Map.of("text","private result");
        });
        handler.receive("envelope-1",payload("connect"),acks::add);
        verifyNoInteractions(commands,web);
        pending.getFirst().run();
        verify(commands).accept(new SlackCommand("T1","U1","C1","connect"),
            com.timiroom.domain.spec.service.DocumentHash.of("socket:envelope-1"));
        verify(web).reply("C1","U1","private result");
    }
    @Test void foreignTeamOrAppNeverReachesCommands() {
        var handler=new SlackSocketHandler("T1","A1",commands,web,Runnable::run);
        for(var wrong:List.of(Map.entry("team_id","T2"),Map.entry("api_app_id","A2"))) {
            var p=payload("connect");p.put(wrong.getKey(),wrong.getValue());
            var acks=new ArrayList<Map<String,Object>>();handler.receive("envelope-1",p,acks::add);
            assertThat(acks).hasSize(1);
        }
        verifyNoInteractions(commands,web);
    }
    @Test void aFailedAckDoesNotExecuteTheCommand() {
        var handler=new SlackSocketHandler("T1","A1",commands,web,Runnable::run);
        assertThatThrownBy(()->handler.receive("envelope-1",payload("connect"),ack->{throw new IllegalStateException();}))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(commands,web);
    }
    @Test void permissionErrorsStayPrivateAndDoNotExposeExceptionText() {
        when(commands.accept(any(),anyString())).thenThrow(new SecurityException("sensitive details"));
        var handler=new SlackSocketHandler("T1","A1",commands,web,Runnable::run);
        handler.receive("envelope-1",payload("spec"),ack->{});
        verify(web).reply(eq("C1"),eq("U1"),argThat(text->text.contains("권한")&&!text.contains("sensitive")));
    }
    @Test void busyReceiverAcknowledgesWithRetryGuidanceWithoutExecuting() {
        var pending=new ArrayList<Runnable>();var acks=new ArrayList<Map<String,Object>>();
        var handler=new SlackSocketHandler("T1","A1",commands,web,pending::add);
        for(int i=0;i<65;i++) handler.receive("envelope-"+i,payload("connect"),acks::add);
        assertThat(pending).hasSize(64);
        assertThat(acks).hasSize(65);
        assertThat(((Map<?,?>)acks.getLast().get("payload")).get("text")).isEqualTo(SlackSocketHandler.RETRY);
        verifyNoInteractions(commands,web);
    }
}
