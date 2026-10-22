package com.timiroom.infra.slack;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SlackFormatTest {
    @Test void linksShowShortLabelsAndUserTextCannotInjectFormatting() {
        assertThat(SlackCommandService.link("https://timiroom.kro.kr/dashboard?projectId=3","열기"))
            .isEqualTo("<https://timiroom.kro.kr/dashboard?projectId=3|열기>");
        // 프로젝트 이름에 <!channel> 같은 문자가 있어도 전체 알림이나 가짜 링크가 되지 않는다
        assertThat(SlackCommandService.escape("A&B <!channel> <http://x|y>")).isEqualTo("A&amp;B &lt;!channel&gt; &lt;http://x|y&gt;");
        assertThat(SlackCommandService.escape(null)).isEmpty();
    }
}
