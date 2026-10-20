package com.timiroom.infra.mcp;
import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component @RequiredArgsConstructor
public class McpResultMapper {
    private final ObjectMapper mapper;
    public McpSchema.CallToolResult success(JsonNode data) {
        var envelope=mapper.createObjectNode().put("schemaVersion",1);envelope.set("data",data);
        return McpSchema.CallToolResult.builder().structuredContent(envelope).addTextContent(envelope.toString()).isError(false).build();
    }
    public McpSchema.CallToolResult error(RuntimeException exception) {
        var known=Map.ofEntries(Map.entry("ACCESS_DENIED","프로젝트 접근 권한을 확인하세요."),Map.entry("INSUFFICIENT_SCOPE","연결에서 해당 기능을 허용해야 합니다."),
            Map.entry("AUTHENTICATION_REQUIRED","티미룸에 다시 연결하세요."),Map.entry("INVALID_INPUT","도구 입력 형식을 확인하세요."),
            Map.entry("INVALID_CURSOR","처음부터 다시 조회하세요."),Map.entry("SPEC_CONFLICT","기준 문서가 바뀌었습니다. 최신 기준으로 변경안을 다시 만드세요."),
            Map.entry("SPEC_NOT_PUBLISHED","PM이 티미룸에서 개발 기준을 먼저 확정해야 합니다."),Map.entry("ARTIFACT_REVIEW_REQUIRED","문서 교차검증을 먼저 완료하세요."),
            Map.entry("IDEMPOTENCY_CONFLICT","같은 요청 키에 다른 입력을 사용할 수 없습니다."),Map.entry("RATE_LIMITED","잠시 후 다시 요청하세요."),
            Map.entry("CONCURRENCY_LIMIT","기존 작업이 끝난 뒤 요청하세요."),Map.entry("PR_CHANGED","PR 커밋이 바뀌었습니다. 최신 SHA로 다시 검사하세요."),
            Map.entry("REPO_NOT_LINKED","프로젝트에 연결된 저장소를 선택하세요."),Map.entry("SERVICE_UNAVAILABLE","티미룸 실행기 연결 상태를 확인하세요."));
        String code=exception instanceof SecurityException?"ACCESS_DENIED":exception instanceof IllegalArgumentException?"INVALID_INPUT":"SERVICE_UNAVAILABLE";
        String message=exception.getMessage()==null?"":exception.getMessage();
        for(var candidate:known.keySet()) if(message.equals(candidate)||message.startsWith(candidate+":")) {code=candidate;break;}
        var envelope=mapper.createObjectNode().put("schemaVersion",1);var error=envelope.putObject("error");
        error.put("code",code).put("message",known.get(code)).put("retryable",Set.of("SERVICE_UNAVAILABLE","RATE_LIMITED","CONCURRENCY_LIMIT").contains(code));
        return McpSchema.CallToolResult.builder().structuredContent(envelope).addTextContent(envelope.toString()).isError(true).build();
    }
}
