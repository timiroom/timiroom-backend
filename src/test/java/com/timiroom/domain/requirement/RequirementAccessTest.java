package com.timiroom.domain.requirement;

import com.timiroom.domain.requirement.entity.Requirement;
import com.timiroom.domain.requirement.enums.RequirementStatus;
import com.timiroom.domain.requirement.repository.RequirementRepository;
import com.timiroom.domain.requirement.service.RequirementService;
import com.timiroom.domain.spec.service.DocumentAccessService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 요구사항은 그 프로젝트 사람만 읽고, PM만 만들고 바꾼다. */
class RequirementAccessTest {

    private final RequirementRepository repository = mock(RequirementRepository.class);
    private final DocumentAccessService access = mock(DocumentAccessService.class);
    private final RequirementService service = new RequirementService(repository, access);

    private void stored() {
        when(repository.findById(30L)).thenReturn(Optional.of(
                Requirement.builder().requirementId(30L).projectId(5L).memberId(8L).title("t").content("c").build()));
    }

    @Test
    void PM이_아니면_다른_프로젝트에_요구사항을_만들_수_없다() {
        doThrow(new SecurityException("ACCESS_DENIED")).when(access).requirePm(5L, 99L);

        assertThatThrownBy(() -> service.create(5L, 99L, "t", "c")).isInstanceOf(SecurityException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void 워크스페이스_밖_사람은_요구사항을_읽을_수_없다() {
        stored();
        doThrow(new SecurityException("ACCESS_DENIED")).when(access).requireRead(5L, 99L);

        assertThatThrownBy(() -> service.getById(30L, 99L)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.getByProject(5L, 99L)).isInstanceOf(SecurityException.class);
    }

    @Test
    void PM이_아니면_생성을_시작하거나_상태를_바꿀_수_없다() {
        stored();
        doThrow(new SecurityException("ACCESS_DENIED")).when(access).requirePm(5L, 99L);

        assertThatThrownBy(() -> service.requireGeneration(30L, 99L)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.updateStatus(30L, 99L, RequirementStatus.COMPLETED))
                .isInstanceOf(SecurityException.class);
        verify(repository, never()).save(any());
    }
}
