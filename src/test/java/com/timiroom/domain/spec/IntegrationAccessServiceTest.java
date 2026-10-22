package com.timiroom.domain.spec;
import com.timiroom.domain.integration.dto.*;
import com.timiroom.domain.integration.entity.IntegrationGrant;
import com.timiroom.domain.integration.repository.IntegrationGrantRepository;
import com.timiroom.domain.integration.service.IntegrationAccessService;
import com.timiroom.domain.spec.service.DocumentAccessService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrationAccessServiceTest {
    final IntegrationGrantRepository grants=mock(IntegrationGrantRepository.class);
    final DocumentAccessService access=mock(DocumentAccessService.class);
    final IntegrationAccessService service=new IntegrationAccessService(grants,access);
    IntegrationGrant grant() { return new IntegrationGrant(2L,"client","authorization",Set.of(1L),Set.of("specs:read"),Instant.now()); }
    IntegrationPrincipal principal(IntegrationGrant grant) {return new IntegrationPrincipal(2L,"client",grant.getGrantId(),Set.of("specs:read"));}
    @Test void everyCallRechecksRevocationAndCurrentMembership() {
        var grant=grant(); when(grants.findById(grant.getGrantId())).thenReturn(Optional.of(grant));
        service.require(principal(grant),1L,IntegrationScope.SPECS_READ);
        verify(access).requireRead(1L,2L);
        grant.revoke(Instant.now());
        assertThatThrownBy(()->service.require(principal(grant),1L,IntegrationScope.SPECS_READ)).isInstanceOf(SecurityException.class);
    }
    @Test void projectScopeOwnerAndClientCannotBeSubstituted() {
        var grant=grant();when(grants.findById(grant.getGrantId())).thenReturn(Optional.of(grant));
        assertThatThrownBy(()->service.require(principal(grant),9L,IntegrationScope.SPECS_READ)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.require(principal(grant),1L,IntegrationScope.CONSISTENCY_READ)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.require(new IntegrationPrincipal(3L,"client",grant.getGrantId(),Set.of("specs:read")),1L,IntegrationScope.SPECS_READ)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.require(new IntegrationPrincipal(2L,"other",grant.getGrantId(),Set.of("specs:read")),1L,IntegrationScope.SPECS_READ)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(access);
    }
    @Test void tokenCannotClaimUnapprovedScopeOrUseExpiredGrant() {
        var grant=new IntegrationGrant(2L,"client","authorization",Set.of(1L),Set.of("specs:read"),Instant.now().minusSeconds(31*86400L));
        when(grants.findById(grant.getGrantId())).thenReturn(Optional.of(grant));
        assertThatThrownBy(()->service.require(principal(grant),1L,IntegrationScope.SPECS_READ)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(access);
    }
    @Test void ownerCanChangeProjectsAndScopesWithoutReconnecting() {
        var grant=grant();when(grants.findById(grant.getGrantId())).thenReturn(Optional.of(grant));
        service.update(2L,grant.getGrantId(),Set.of(1L,5L),Set.of("specs:read","specs:propose"));
        verify(access).requireRead(5L,2L);
        assertThat(grant.projects()).containsExactlyInAnyOrder(1L,5L);
        assertThat(grant.scopeSet()).containsExactlyInAnyOrder("specs:read","specs:propose");
        // 새 범위가 다음 요청부터 바로 적용된다
        service.require(new IntegrationPrincipal(2L,"client",grant.getGrantId(),grant.scopeSet()),5L,IntegrationScope.SPECS_PROPOSE);
    }
    @Test void scopeUpdateRejectsOtherMembersRevokedGrantsAndBadInput() {
        var grant=grant();when(grants.findById(grant.getGrantId())).thenReturn(Optional.of(grant));
        assertThatThrownBy(()->service.update(3L,grant.getGrantId(),Set.of(1L),Set.of("specs:read"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.update(2L,grant.getGrantId(),Set.of(),Set.of("specs:read"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.update(2L,grant.getGrantId(),Set.of(1L),Set.of("admin:all"))).isInstanceOf(RuntimeException.class);
        doThrow(new SecurityException("ACCESS_DENIED")).when(access).requireRead(9L,2L);
        assertThatThrownBy(()->service.update(2L,grant.getGrantId(),Set.of(9L),Set.of("specs:read"))).isInstanceOf(SecurityException.class);
        grant.revoke(Instant.now());
        assertThatThrownBy(()->service.update(2L,grant.getGrantId(),Set.of(1L),Set.of("specs:read"))).isInstanceOf(SecurityException.class);
    }
}
