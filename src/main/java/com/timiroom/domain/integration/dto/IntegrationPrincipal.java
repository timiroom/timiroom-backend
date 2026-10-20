package com.timiroom.domain.integration.dto;
import java.util.*;
/** Created only by the resource-server authenticator, never deserialized from tool input. */
public record IntegrationPrincipal(Long memberId,String clientId,UUID grantId,Set<String> scopes) {
    public IntegrationPrincipal { scopes=Set.copyOf(scopes); }
}
