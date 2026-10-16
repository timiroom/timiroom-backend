package com.timiroom.domain.aikey;

import com.timiroom.domain.aikey.service.ExternalUrlGuard;
import com.timiroom.domain.aikey.service.SecretCipher;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiKeySecurityTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void 비밀값은_암호화해_저장하고_되돌릴_수_있다() {
        SecretCipher cipher = new SecretCipher(KEY);
        String encrypted = cipher.encrypt("sk-secret-value");
        assertThat(encrypted).startsWith("v1:").doesNotContain("sk-secret-value");
        assertThat(cipher.encrypt("sk-secret-value")).isNotEqualTo(encrypted);
        assertThat(cipher.decrypt(encrypted)).isEqualTo("sk-secret-value");
    }

    @Test
    void 암호화_키가_없으면_기능이_꺼진다() {
        SecretCipher cipher = new SecretCipher("");
        assertThat(cipher.isEnabled()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 잘못된_길이의_키는_거절한다() {
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void AI_주소는_https_공인_주소만_받는다() {
        assertThatThrownBy(() -> ExternalUrlGuard.normalize("http://api.openai.com/v1")).hasMessageContaining("https");
        assertThatThrownBy(() -> ExternalUrlGuard.normalize("https://127.0.0.1/v1")).hasMessageContaining("내부망");
        assertThatThrownBy(() -> ExternalUrlGuard.normalize("https://10.0.0.5/v1")).hasMessageContaining("내부망");
        assertThatThrownBy(() -> ExternalUrlGuard.normalize("https://169.254.169.254/latest")).hasMessageContaining("내부망");
        assertThatThrownBy(() -> ExternalUrlGuard.normalize("https://user:pw@example.com/v1")).hasMessageContaining("계정");
        assertThat(ExternalUrlGuard.normalize("https://8.8.8.8/v1/")).isEqualTo("https://8.8.8.8/v1");
    }
}
