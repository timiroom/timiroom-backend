package com.timiroom.domain.requirement;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RequirementService {

    private final RequirementRepository requirementRepository;

    @Transactional
    public Requirement create(Long projectId, Long memberId, String title, String content) {
        return requirementRepository.save(Requirement.builder()
                .projectId(projectId)
                .memberId(memberId)
                .title(title)
                .content(content)
                .build());
    }

    @Transactional(readOnly = true)
    public Requirement getById(Long requirementId) {
        return requirementRepository.findById(requirementId)
                .orElseThrow(() -> new IllegalArgumentException("요구사항을 찾을 수 없습니다: " + requirementId));
    }

    @Transactional(readOnly = true)
    public List<Requirement> getByProject(Long projectId) {
        return requirementRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public List<Requirement> getMyRequirements(Long memberId) {
        return requirementRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }

    @Transactional
    public void updateStatus(Long requirementId, RequirementStatus status) {
        requirementRepository.findById(requirementId)
                .ifPresent(r -> {
                    r.updateStatus(status);
                    requirementRepository.save(r);
                });
    }
}
