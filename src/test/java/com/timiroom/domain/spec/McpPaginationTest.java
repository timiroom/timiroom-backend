package com.timiroom.domain.spec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.integration.dto.IntegrationPrincipal;
import com.timiroom.infra.mcp.McpPaginationService;
import com.timiroom.domain.graph.dto.GraphResponse;
import com.timiroom.domain.spec.service.DocumentHash;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class McpPaginationTest {
    final String secret="test-only-cursor-signing-key-32-characters";
    final ObjectMapper mapper=new ObjectMapper();
    final Clock clock=Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"),ZoneOffset.UTC);
    final McpPaginationService pages=new McpPaginationService(secret,mapper,clock);
    final IntegrationPrincipal actor=new IntegrationPrincipal(2L,"client",UUID.randomUUID(),Set.of("specs:read"));
    @Test void impactCursorWorksAcrossServersWithDifferentMetadataIterationOrder() throws Exception {
        var firstMeta=new LinkedHashMap<String,Object>();
        firstMeta.put("path","/api/v1/items");firstMeta.put("method","POST");
        var secondMeta=new LinkedHashMap<String,Object>();
        secondMeta.put("method","POST");secondMeta.put("path","/api/v1/items");
        var firstNode=new GraphResponse.Node("api:POST:/api/v1/items","POST /api/v1/items","api",null,false,null,false,firstMeta);
        var secondNode=new GraphResponse.Node("api:POST:/api/v1/items","POST /api/v1/items","api",null,false,null,false,secondMeta);
        String first=mapper.writeValueAsString(firstNode),second=mapper.writeValueAsString(secondNode);
        var page=pages.page(actor,"impact:"+DocumentHash.of(first),first,null,20);
        var next=pages.page(actor,"impact:"+DocumentHash.of(second),second,page.nextCursor(),20);
        assertThat(first).isEqualTo(second);
        assertThat(next.content()).isEqualTo(first.substring(20,40));
    }
    @Test void allPagesReassembleExactUnicodeContent() {
        String original="명세😀한글\n".repeat(31);String cursor=null;var reassembled=new StringBuilder();
        do {var page=pages.page(actor,"document:hash",original,cursor,5);reassembled.append(page.content());cursor=page.nextCursor();
            assertThat(page.totalChars()).isEqualTo(original.length());
            assertThat(new String(page.content().getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(page.content());
        } while(cursor!=null);
        assertThat(reassembled.toString()).isEqualTo(original);
    }
    @Test void cursorCannotBeTamperedOrUsedByAnotherActorDocumentOrGrant() {
        var cursor=pages.page(actor,"doc1:hash","abcdefghijklmnop",null,4).nextCursor();
        assertThatThrownBy(()->pages.page(actor,"doc1:hash","abcdefghijklmnop",cursor.substring(0,cursor.length()-1)+"!",4)).hasMessage("INVALID_CURSOR");
        assertThatThrownBy(()->pages.page(actor,"doc2:hash","abcdefghijklmnop",cursor,4)).hasMessage("INVALID_CURSOR");
        assertThatThrownBy(()->pages.page(new IntegrationPrincipal(3L,"client",actor.grantId(),actor.scopes()),"doc1:hash","abcdefghijklmnop",cursor,4)).hasMessage("INVALID_CURSOR");
        assertThatThrownBy(()->pages.page(new IntegrationPrincipal(2L,"client",UUID.randomUUID(),actor.scopes()),"doc1:hash","abcdefghijklmnop",cursor,4)).hasMessage("INVALID_CURSOR");
    }
    @Test void expiredCursorMustRestart() {
        var cursor=pages.page(actor,"doc","abcdef",null,2).nextCursor();
        var later=new McpPaginationService(secret,mapper,Clock.offset(clock,Duration.ofMinutes(16)));
        assertThatThrownBy(()->later.page(actor,"doc","abcdef",cursor,2)).hasMessage("INVALID_CURSOR");
    }
}
