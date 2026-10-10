package com.timiroom.infra.github;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GithubClientTest {

    @Test
    void score가_0이어도_확정경고면_판정보류가_아니다() {
        assertThat(GithubClient.consistencyCheckTitle(0, false))
                .isEqualTo("검토 필요 · 0/100");
    }

    @Test
    void 근거부족일_때만_판정보류로_표시한다() {
        assertThat(GithubClient.consistencyCheckTitle(0, true))
                .isEqualTo("판정 보류 · 근거 확인 필요");
    }
}
