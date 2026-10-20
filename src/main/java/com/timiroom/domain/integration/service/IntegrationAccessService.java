package com.timiroom.domain.integration.service;
import com.timiroom.domain.integration.dto.*;
import com.timiroom.domain.integration.entity.IntegrationGrant;
import com.timiroom.domain.integration.repository.IntegrationGrantRepository;
import com.timiroom.domain.spec.service.DocumentAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class IntegrationAccessService {
    private final IntegrationGrantRepository grants;
    private final DocumentAccessService access;
    @Transactional(readOnly=true)
    public IntegrationGrant require(IntegrationPrincipal actor,Long projectId,IntegrationScope scope) {
        var grant=requireGrant(actor,scope);
        if(projectId==null || !grant.projects().contains(projectId)) throw new SecurityException("ACCESS_DENIED");
        access.requireRead(projectId,actor.memberId());
        return grant;
    }
    @Transactional(readOnly=true)
    public IntegrationGrant requireGrant(IntegrationPrincipal actor,IntegrationScope scope) {
        if(actor==null || !actor.scopes().contains(scope.value())) throw new SecurityException("INSUFFICIENT_SCOPE");
        var grant=grants.findById(actor.grantId()).orElseThrow(()->new SecurityException("ACCESS_DENIED"));
        if(!grant.active(Instant.now()) || !Objects.equals(grant.getMemberId(),actor.memberId())
                || !Objects.equals(grant.getClientId(),actor.clientId()) || !grant.scopeSet().contains(scope.value()))
            throw new SecurityException("ACCESS_DENIED");
        return grant;
    }
    @Transactional
    public IntegrationGrant create(Long member,String client,String authorization,Set<Long> projects,Set<String> scopes) {
        projects.forEach(project->access.requireRead(project,member));
        return grants.save(new IntegrationGrant(member,client,authorization,projects,scopes,Instant.now()));
    }
    @Transactional
    public void revoke(Long member,UUID id) {
        var grant=grants.findById(id).orElseThrow(()->new SecurityException("ACCESS_DENIED"));
        if(!Objects.equals(grant.getMemberId(),member)) throw new SecurityException("ACCESS_DENIED");
        grant.revoke(Instant.now());
    }
}
