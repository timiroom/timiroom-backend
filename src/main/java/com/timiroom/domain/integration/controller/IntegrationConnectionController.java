package com.timiroom.domain.integration.controller;
import com.timiroom.domain.integration.service.*;
import com.timiroom.domain.integration.repository.IntegrationGrantRepository;
import com.timiroom.domain.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.endpoint.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
import java.time.Instant;
import java.util.*;

@RestController @RequiredArgsConstructor
@ConditionalOnProperty(name="integration.enabled",havingValue="true")
public class IntegrationConnectionController {
    private final IntegrationAccessService access;
    private final IntegrationActorResolver actors;
    private final IntegrationGrantRepository grants;
    private final OAuth2AuthorizationService authorizations;
    private final RegisteredClientRepository clients;
    private final ProjectService projects;
    @Value("${integration.issuer:http://localhost:8080}") private String issuer;
    @GetMapping("/api/v1/integrations/csrf")
    public Map<String,String> csrf(CsrfToken token) {
        return Map.of("headerName",token.getHeaderName(),"parameterName",token.getParameterName(),"token",token.getToken());
    }
    @GetMapping("/api/v1/integrations/connections")
    public List<Map<String,Object>> connections(Authentication authentication) {
        Long member=actors.memberId(authentication);
        return grants.findByMemberIdOrderByCreatedAtDesc(member).stream().map(grant->{
            Map<String,Object> dto=new LinkedHashMap<>();dto.put("connectionId",grant.getGrantId());dto.put("clientId",grant.getClientId());
            dto.put("projectIds",grant.projects());dto.put("scopes",grant.scopeSet());dto.put("createdAt",grant.getCreatedAt());
            dto.put("expiresAt",grant.getExpiresAt());dto.put("revokedAt",grant.getRevokedAt());dto.put("active",grant.active(Instant.now()));
            dto.put("mcpUrl",issuer+"/mcp");return dto;
        }).toList();
    }
    @DeleteMapping("/api/v1/integrations/connections/{id}")
    public ResponseEntity<Void> revoke(Authentication authentication,@PathVariable UUID id) {
        access.revoke(actors.memberId(authentication),id);return ResponseEntity.noContent().build();
    }
    @GetMapping(value="/integrations/oauth/consent",produces=MediaType.TEXT_HTML_VALUE)
    public String consent(Authentication authentication,CsrfToken csrf,@RequestParam("client_id") String clientId,@RequestParam String state) {
        Long member=actors.memberId(authentication);
        var authorization=authorizations.findByToken(state,new OAuth2TokenType(OAuth2ParameterNames.STATE));
        var client=clients.findByClientId(clientId);
        if(authorization==null || client==null || !authorization.getRegisteredClientId().equals(client.getId())
                || !member.toString().equals(authorization.getPrincipalName())) throw new SecurityException("ACCESS_DENIED");
        OAuth2AuthorizationRequest request=authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        if(request==null || !(issuer+"/mcp").equals(request.getAdditionalParameters().get("resource"))) throw new SecurityException("ACCESS_DENIED");
        var html=new StringBuilder(IntegrationPageView.start("AI 개발 도구 연결", "consent"));
        String clientName=switch(clientId) {
            case "timiroom-codex" -> "Codex";
            case "timiroom-claude" -> "Claude Code";
            default -> client.getClientName();
        };
        html.append("<p><strong>").append(escape(clientName)).append("</strong>에 허용할 프로젝트와 기능을 선택하세요. 티미룸 화면에서 언제든 연결을 해제할 수 있습니다.</p>");
        html.append("<form method=post action=\"").append(escape(issuer+"/oauth2/authorize")).append("\">");
        hidden(html,"client_id",clientId);hidden(html,"state",state);hidden(html,csrf.getParameterName(),csrf.getToken());
        html.append("<fieldset><legend>프로젝트</legend><label class=field-label for=projects>연결할 프로젝트</label><select id=projects name=project_id multiple required size=5>");
        for(var project:projects.getMyProjects(member)) html.append("<option value=\"").append(project.getProjectId()).append("\">").append(escape(project.getProjectName())).append("</option>");
        html.append("</select><small>여러 프로젝트는 Ctrl 또는 Command 키를 누르고 선택하세요.</small></fieldset><fieldset><legend>허용할 기능</legend>");
        var labels=Map.of("projects:read","프로젝트 목록 조회","specs:read","확정 명세와 변경안 조회","specs:propose","문서 수정안 생성",
            "consistency:run","티미룸 정합성 검사 실행","consistency:read","정합성 검사 결과 조회");
        for(var scope:request.getScopes().stream().sorted().toList()) {
            if(!labels.containsKey(scope)) throw new SecurityException("INVALID_SCOPE");
            html.append("<label class=permission><input type=checkbox name=scope value=\"").append(escape(scope)).append("\"");
            if(Set.of("projects:read","specs:read").contains(scope)) html.append(" checked");
            html.append("><span>").append(escape(labels.get(scope))).append("</span></label>");
        }
        html.append("</fieldset><p class=notice>수정안은 원본에 바로 반영되지 않습니다. 티미룸의 교차검증을 거쳐 PM이 승인합니다.</p>")
            .append("<div class=actions><button class=primary type=submit>선택한 범위로 연결</button><button type=submit name=cancel value=1 formnovalidate>취소</button></div></form>").append(IntegrationPageView.end());
        return html.toString();
    }
    private void hidden(StringBuilder html,String key,String value) {
        html.append("<input type=hidden name=\"").append(escape(key)).append("\" value=\"").append(escape(value)).append("\">");
    }
    private String escape(String value) {return HtmlUtils.htmlEscape(value==null?"":value);}
}
