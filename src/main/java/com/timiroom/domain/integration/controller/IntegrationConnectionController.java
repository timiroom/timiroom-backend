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
    @Value("${frontend.url:http://localhost:3000}") private String frontend;
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
    public record ScopeUpdate(List<Long> projectIds,List<String> scopes) {}
    @PatchMapping("/api/v1/integrations/connections/{id}")
    public ResponseEntity<Void> update(Authentication authentication,@PathVariable UUID id,@RequestBody ScopeUpdate body) {
        if(body==null || body.projectIds()==null || body.scopes()==null) throw new IllegalArgumentException("INVALID_GRANT");
        access.update(actors.memberId(authentication),id,new LinkedHashSet<>(body.projectIds()),new LinkedHashSet<>(body.scopes()));
        return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/api/v1/integrations/connections/{id}")
    public ResponseEntity<Void> revoke(Authentication authentication,@PathVariable UUID id) {
        access.revoke(actors.memberId(authentication),id);return ResponseEntity.noContent().build();
    }
    /** 예전 서버 그림 승인 화면 주소 — 티미룸 설정 → 연결된 도구 화면으로 넘긴다. */
    @GetMapping("/integrations/oauth/consent")
    public ResponseEntity<Void> consentPage(@RequestParam("client_id") String clientId,@RequestParam String state,
                                            @RequestParam(required=false) String scope) {
        var target=org.springframework.web.util.UriComponentsBuilder.fromUriString(frontend.replaceAll("/+$","")+"/settings/connections")
            .queryParam("client_id",clientId).queryParam("state",state);
        if(scope!=null) target.queryParam("scope",scope);
        return ResponseEntity.status(HttpStatus.FOUND).location(target.encode().build().toUri()).build();
    }

    /**
     * 승인 화면(프론트 설정 → 연결된 도구)이 그릴 내용. 승인 처리 자체는 그대로 /oauth2/authorize(POST)가 한다.
     * state로 찾은 승인 요청이 이 로그인 사용자·이 클라이언트·티미룸 MCP 자원의 것인지 확인한 뒤에만 내려준다.
     */
    @GetMapping("/api/v1/integrations/consent")
    public Map<String,Object> consent(Authentication authentication,CsrfToken csrf,@RequestParam("client_id") String clientId,@RequestParam String state) {
        Long member=actors.memberId(authentication);
        var authorization=authorizations.findByToken(state,new OAuth2TokenType(OAuth2ParameterNames.STATE));
        var client=clients.findByClientId(clientId);
        if(authorization==null || client==null || !authorization.getRegisteredClientId().equals(client.getId())
                || !member.toString().equals(authorization.getPrincipalName())) throw new SecurityException("ACCESS_DENIED");
        OAuth2AuthorizationRequest request=authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        if(request==null || !(issuer+"/mcp").equals(request.getAdditionalParameters().get("resource"))) throw new SecurityException("ACCESS_DENIED");
        String clientName=switch(clientId) {
            case "timiroom-codex" -> "Codex";
            case "timiroom-claude" -> "Claude Code";
            default -> client.getClientName();
        };
        var scopes=new ArrayList<Map<String,Object>>();
        for(var scope:request.getScopes().stream().sorted().toList()) {
            var label=SCOPE_LABELS.get(scope);
            if(label==null) throw new SecurityException("INVALID_SCOPE");
            scopes.add(Map.of("value",scope,"label",label[0],"description",label[1],"defaultChecked",Set.of("projects:read","specs:read").contains(scope)));
        }
        var projectList=projects.getMyProjects(member).stream()
            .map(p->Map.<String,Object>of("projectId",p.getProjectId(),"name",p.getProjectName())).toList();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("clientId",clientId);result.put("clientName",clientName);result.put("state",state);
        result.put("authorizeUrl",issuer+"/oauth2/authorize");
        result.put("csrf",Map.of("parameterName",csrf.getParameterName(),"token",csrf.getToken()));
        result.put("projects",projectList);result.put("scopes",scopes);
        return result;
    }
    private static final Map<String,String[]> SCOPE_LABELS=Map.of(
        "projects:read",new String[]{"프로젝트 정보 조회","선택한 프로젝트의 이름과 연결된 저장소를 확인합니다."},
        "specs:read",new String[]{"명세 조회","확정된 PRD·기능 명세·API·ERD와 변경안을 확인합니다."},
        "specs:propose",new String[]{"수정안 작성","명세 변경안을 작성합니다. 원본에는 교차검증과 PM 승인 뒤에만 반영됩니다."},
        "consistency:run",new String[]{"검사 요청","문서 교차검증과 PR 정합성 검사를 요청합니다. AI 사용량이 차감됩니다."},
        "consistency:read",new String[]{"검사 결과 조회","교차검증과 PR 검사 결과를 확인합니다."});
}
