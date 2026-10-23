package com.timiroom.domain.mock;

import com.timiroom.domain.mock.service.MockUrlSigner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Mock 주소는 프로젝트 번호만으로 열 수 없다. */
class MockUrlSignerTest {

    private final MockUrlSigner signer = new MockUrlSigner("test-only-mock-secret-at-least-32-bytes");

    @Test
    void 서명한_키로만_프로젝트를_찾는다() {
        String key = signer.keyFor(7L);

        assertThat(key).startsWith("7-");
        assertThat(signer.projectIdOf(key)).contains(7L);
    }

    @Test
    void 번호만_있거나_서명을_바꾸면_열리지_않는다() {
        String key = signer.keyFor(7L);
        String otherProject = "8" + key.substring(1);

        assertThat(signer.projectIdOf("7")).isEmpty();
        assertThat(signer.projectIdOf("7-")).isEmpty();
        assertThat(signer.projectIdOf(otherProject)).isEmpty();
        assertThat(signer.projectIdOf(key + "x")).isEmpty();
        assertThat(signer.projectIdOf("abc-def")).isEmpty();
    }

    @Test
    void 비밀값이_다르면_다른_키가_나온다() {
        MockUrlSigner other = new MockUrlSigner("another-mock-secret-value-of-32-bytes");

        assertThat(other.projectIdOf(signer.keyFor(7L))).isEmpty();
    }
}
