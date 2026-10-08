package com.timiroom.domain.integration.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Entity @Table(name="integration_grant") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class IntegrationGrant {
    @Id private UUID grantId;
    @Column(nullable=false) private Long memberId;
    @Column(nullable=false,length=128) private String clientId;
    @Column(nullable=false,unique=true,length=100) private String authorizationId;
    @Column(nullable=false,columnDefinition="TEXT") private String projectIds;
    @Column(nullable=false,columnDefinition="TEXT") private String scopes;
    @Column(nullable=false) private Instant createdAt;
    @Column(nullable=false) private Instant expiresAt;
    private Instant revokedAt;
    public IntegrationGrant(Long memberId,String clientId,String authorizationId,Set<Long> projects,Set<String> scopes,Instant now) {
        if(memberId==null || memberId<=0 || clientId==null || clientId.isBlank() || authorizationId==null
                || projects==null || projects.isEmpty() || projects.size()>50 || projects.stream().anyMatch(p->p==null||p<=0)
                || scopes==null || scopes.isEmpty()) throw new IllegalArgumentException("INVALID_GRANT");
        scopes.forEach(com.timiroom.domain.integration.dto.IntegrationScope::from);
        grantId=UUID.randomUUID();this.memberId=memberId;this.clientId=clientId;this.authorizationId=authorizationId;
        projectIds=projects.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        this.scopes=scopes.stream().sorted().collect(Collectors.joining(" "));
        createdAt=now;expiresAt=now.plusSeconds(30*86400L);
    }
    public Set<Long> projects() {return Arrays.stream(projectIds.split(",")).map(Long::valueOf).collect(Collectors.toUnmodifiableSet());}
    public Set<String> scopeSet() {return Set.of(scopes.split(" "));}
    public boolean active(Instant now) {return revokedAt==null && expiresAt.isAfter(now);}
    public void revoke(Instant now) {if(revokedAt==null) revokedAt=now;}
}
