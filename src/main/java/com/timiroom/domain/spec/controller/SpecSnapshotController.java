package com.timiroom.domain.spec.controller;

import com.timiroom.domain.spec.service.SpecSnapshotService;
import com.timiroom.domain.spec.dto.SpecSnapshotDto;
import com.timiroom.domain.integration.service.IntegrationActorResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{project}/spec-snapshots")
@ConditionalOnProperty(name="integration.enabled",havingValue="true")
public class SpecSnapshotController {
    private final SpecSnapshotService snapshots;
    private final IntegrationActorResolver actors;
    @GetMapping("/latest") public SpecSnapshotDto latest(Authentication auth,@PathVariable Long project) {
        return snapshots.latest(project,actors.memberId(auth));
    }
    @GetMapping("/{id}") public SpecSnapshotDto get(Authentication auth,@PathVariable Long project,@PathVariable UUID id) {
        return snapshots.get(project,actors.memberId(auth),id);
    }
    @PostMapping public SpecSnapshotDto publish(Authentication auth,@PathVariable Long project) {
        return snapshots.publish(project,actors.memberId(auth));
    }
}
