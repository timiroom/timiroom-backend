package com.timiroom.domain.commit;

import com.timiroom.domain.commit.entity.Commit;
import com.timiroom.domain.commit.repository.CommitRepository;
import com.timiroom.domain.commit.service.CommitService;
import com.timiroom.domain.project.repository.ProjectMemberRepository;
import com.timiroom.domain.spec.service.DocumentAccessService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 커밋 기록은 내가 볼 수 있는 프로젝트 것만. */
class CommitAccessTest {

    private final CommitRepository commits = mock(CommitRepository.class);
    private final ProjectMemberRepository members = mock(ProjectMemberRepository.class);
    private final DocumentAccessService access = mock(DocumentAccessService.class);
    private final CommitService service = new CommitService(commits, members, access);

    @Test
    void 전체_목록은_내가_참여한_프로젝트만_돌려준다() {
        when(members.findProjectIdsByMemberId(8L)).thenReturn(List.of(5L, 6L));
        Commit mine = Commit.builder().projectId(5L).memberId(8L).message("m").build();
        when(commits.findByProjectIdInOrderByCreatedAtDesc(List.of(5L, 6L))).thenReturn(List.of(mine));

        assertThat(service.getMine(8L)).containsExactly(mine);
    }

    @Test
    void 참여한_프로젝트가_없으면_빈_목록이다() {
        when(members.findProjectIdsByMemberId(8L)).thenReturn(List.of());

        assertThat(service.getMine(8L)).isEmpty();
        verify(commits, never()).findByProjectIdInOrderByCreatedAtDesc(any());
    }

    @Test
    void 남의_프로젝트_커밋은_읽거나_남길_수_없다() {
        doThrow(new SecurityException("ACCESS_DENIED")).when(access).requireRead(5L, 99L);
        doThrow(new SecurityException("ACCESS_DENIED")).when(access).requireProjectMember(5L, 99L);

        assertThatThrownBy(() -> service.getByProject(5L, 99L)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.create(5L, 99L, "m")).isInstanceOf(SecurityException.class);
        verify(commits, never()).save(any());
    }
}
