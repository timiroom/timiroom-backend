package com.timiroom.domain.requirement.service;

import com.timiroom.domain.requirement.enums.RequirementStatus;
import com.timiroom.domain.requirement.entity.Requirement;
import com.timiroom.domain.requirement.repository.RequirementRepository;
import com.timiroom.domain.spec.service.DocumentAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 요구사항은 그 프로젝트의 문서를 새로 만드는 입력이다.
 * 읽기는 워크스페이스 멤버, 만들기·상태 변경·생성 시작은 프로젝트 PM만 한다(생성 결과가 모든 문서를 바꾸기 때문).
 */
@Service
@RequiredArgsConstructor
public class RequirementService {

    private final RequirementRepository requirementRepository;
    private final DocumentAccessService access;

    @Transactional
    public Requirement create(Long projectId, Long memberId, String title, String content) {
        access.requirePm(projectId, memberId);
        return requirementRepository.save(Requirement.builder()
                .projectId(projectId)
                .memberId(memberId)
                .title(title)
                .content(content)
                .build());
    }

    /** 화면 요청용. 워크스페이스 멤버만 읽는다. */
    @Transactional(readOnly = true)
    public Requirement getById(Long requirementId, Long memberId) {
        Requirement requirement = getById(requirementId);
        access.requireRead(requirement.getProjectId(), memberId);
        return requirement;
    }

    /** 생성을 시작하기 전에 부른다. 프로젝트 PM만 문서 생성을 시작할 수 있다. */
    @Transactional(readOnly = true)
    public Requirement requireGeneration(Long requirementId, Long memberId) {
        Requirement requirement = getById(requirementId);
        access.requirePm(requirement.getProjectId(), memberId);
        return requirement;
    }

    /** 서버 내부(생성 완료·실패 처리)용. 권한을 확인하지 않는다. */
    @Transactional(readOnly = true)
    public Requirement getById(Long requirementId) {
        return requirementRepository.findById(requirementId)
                .orElseThrow(() -> new IllegalArgumentException("요구사항을 찾을 수 없습니다: " + requirementId));
    }

    @Transactional(readOnly = true)
    public List<Requirement> getByProject(Long projectId, Long memberId) {
        access.requireRead(projectId, memberId);
        return requirementRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    /** 서버 내부(최신 문서 찾기)용. 권한을 확인하지 않는다. */
    @Transactional(readOnly = true)
    public List<Requirement> getByProject(Long projectId) {
        return requirementRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public List<Requirement> getMyRequirements(Long memberId) {
        return requirementRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }

    /** 화면 요청용 상태 변경. 프로젝트 PM만 바꾼다. */
    @Transactional
    public void updateStatus(Long requirementId, Long memberId, RequirementStatus status) {
        Requirement requirement = getById(requirementId);
        access.requirePm(requirement.getProjectId(), memberId);
        requirement.updateStatus(status);
        requirementRepository.save(requirement);
    }

    /** 서버 내부(생성 진행 상태)용. 권한을 확인하지 않는다. */
    @Transactional
    public void updateStatus(Long requirementId, RequirementStatus status) {
        requirementRepository.findById(requirementId)
                .ifPresent(r -> {
                    r.updateStatus(status);
                    requirementRepository.save(r);
                });
    }
}
