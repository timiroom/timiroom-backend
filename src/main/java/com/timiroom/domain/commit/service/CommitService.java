package com.timiroom.domain.commit.service;

import com.timiroom.domain.commit.entity.Commit;
import com.timiroom.domain.commit.repository.CommitRepository;
import com.timiroom.domain.project.repository.ProjectMemberRepository;
import com.timiroom.domain.spec.service.DocumentAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 프로젝트 커밋 기록. 내가 볼 수 있는 프로젝트의 기록만 다룬다. */
@Service
@RequiredArgsConstructor
public class CommitService {

    private final CommitRepository commitRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final DocumentAccessService access;

    /** 내가 참여한 프로젝트들의 커밋 */
    @Transactional(readOnly = true)
    public List<Commit> getMine(Long memberId) {
        if (memberId == null) throw new SecurityException("ACCESS_DENIED");
        List<Long> projectIds = projectMemberRepository.findProjectIdsByMemberId(memberId);
        if (projectIds.isEmpty()) return List.of();
        return commitRepository.findByProjectIdInOrderByCreatedAtDesc(projectIds);
    }

    @Transactional(readOnly = true)
    public List<Commit> getByProject(Long projectId, Long memberId) {
        access.requireRead(projectId, memberId);
        return commitRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional
    public Commit create(Long projectId, Long memberId, String message) {
        access.requireProjectMember(projectId, memberId);
        return commitRepository.save(Commit.builder()
                .projectId(projectId)
                .memberId(memberId)
                .message(message)
                .build());
    }
}
