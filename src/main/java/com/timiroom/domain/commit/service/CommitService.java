package com.timiroom.domain.commit.service;

import com.timiroom.domain.commit.entity.Commit;
import com.timiroom.domain.commit.repository.CommitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CommitService {

    private final CommitRepository commitRepository;

    @Transactional(readOnly = true)
    public List<Commit> getAll() {
        return commitRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<Commit> getByProject(Long projectId) {
        return commitRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional
    public Commit create(Long projectId, Long memberId, String message) {
        return commitRepository.save(Commit.builder()
                .projectId(projectId)
                .memberId(memberId)
                .message(message)
                .build());
    }
}
