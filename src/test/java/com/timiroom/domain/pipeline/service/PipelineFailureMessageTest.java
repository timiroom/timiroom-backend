package com.timiroom.domain.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PipelineFailureMessageTest {

    @Test
    void 관리자_검토_사유는_다음_행동을_알려주는_문장으로_바꾼다() {
        assertThat(PipelineService.friendlyFailure("자동 검증 실패 — 관리자 검토가 필요합니다"))
                .contains("다시 생성해 주세요");
    }

    @Test
    void 빈_사유는_기본_안내로_바꾼다() {
        assertThat(PipelineService.friendlyFailure(null)).contains("다시 시도해 주세요");
    }

    @Test
    void 이미_읽을_수_있는_사유는_그대로_둔다() {
        assertThat(PipelineService.friendlyFailure("생성이 중간에 멈췄습니다. 다시 시도해 주세요."))
                .isEqualTo("생성이 중간에 멈췄습니다. 다시 시도해 주세요.");
    }
}
