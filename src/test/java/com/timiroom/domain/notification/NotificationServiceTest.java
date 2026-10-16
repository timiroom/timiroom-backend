package com.timiroom.domain.notification;

import com.timiroom.domain.notification.entity.Notification;
import com.timiroom.domain.notification.enums.NotificationReferenceType;
import com.timiroom.domain.notification.enums.NotificationType;
import com.timiroom.domain.notification.repository.NotificationRepository;
import com.timiroom.domain.notification.service.NotificationService;
import com.timiroom.domain.notification.service.NotificationService.NotificationNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationServiceTest {
    NotificationRepository repository = mock(NotificationRepository.class);
    NotificationService service = new NotificationService(repository);

    @Test
    void 알림_생성시_projectId를_저장한다() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        Notification n = service.create(8L, 5L, NotificationType.PIPELINE_FAILED, "파이프라인 실패", "오류",
                NotificationReferenceType.PIPELINE, 9L);

        assertThat(n.getProjectId()).isEqualTo(5L);
        assertThat(n.getMemberId()).isEqualTo(8L);
        assertThat(n.getReferenceId()).isEqualTo(9L);
    }

    @Test
    void 본인_알림은_읽음_처리된다() {
        Notification mine = Notification.builder().notificationId(17L).memberId(8L)
                .type(NotificationType.PR_CONSISTENCY_REVIEW).title("t").build();
        when(repository.findByNotificationIdAndMemberId(17L, 8L)).thenReturn(Optional.of(mine));

        service.markAsRead(8L, 17L);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isRead()).isTrue();
    }

    @Test
    void 다른_회원의_알림은_읽음_처리할_수_없다() {
        // 17번은 다른 회원(9번)의 알림이라 8번 회원 조건으로는 조회되지 않는다.
        when(repository.findByNotificationIdAndMemberId(17L, 8L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markAsRead(8L, 17L)).isInstanceOf(NotificationNotFoundException.class);
        verify(repository, never()).save(any());
        verify(repository, never()).findById(any());
    }
}
