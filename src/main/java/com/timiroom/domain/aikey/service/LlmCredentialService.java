package com.timiroom.domain.aikey.service;

import com.timiroom.domain.aikey.LlmProvider;
import com.timiroom.domain.aikey.entity.LlmCredential;
import com.timiroom.domain.aikey.repository.LlmCredentialRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 사용자 AI 키 등록·확인·사용 설정. 키 원문은 이 서비스 밖으로 나가지 않는다(AI 서버 전달 제외). */
@Slf4j
@Service
public class LlmCredentialService {

    private static final int MAX_KEYS_PER_MEMBER = 5;

    /** AI 서버로 넘기는 사용자 키 설정. toString에 키를 남기지 않는다. */
    public record ActiveKey(String provider, String baseUrl, String apiKey, String model) {
        @Override
        public String toString() {
            return "ActiveKey[provider=" + provider + ", baseUrl=" + baseUrl + ", model=" + model + "]";
        }
    }

    private final LlmCredentialRepository credentials;
    private final SecretCipher cipher;
    private final WebClient webClient;

    public LlmCredentialService(LlmCredentialRepository credentials, SecretCipher cipher) {
        this.credentials = credentials;
        this.cipher = cipher;
        this.webClient = WebClient.builder().build();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long memberId) {
        return credentials.findByMemberIdOrderByCreatedAtAsc(memberId).stream().map(LlmCredentialService::view).toList();
    }

    /** 키를 확인한 뒤 저장하고 바로 사용 중으로 바꾼다. 확인에 실패하면 저장하지 않는다. */
    @Transactional
    public Map<String, Object> register(Long memberId, String providerName, String baseUrl, String model, String apiKey) {
        if (!cipher.isEnabled()) throw new IllegalStateException("이 서버에서는 AI 키 등록을 쓸 수 없습니다");
        if (credentials.countByMemberId(memberId) >= MAX_KEYS_PER_MEMBER) {
            throw new IllegalArgumentException("AI 키는 " + MAX_KEYS_PER_MEMBER + "개까지 등록할 수 있습니다");
        }
        LlmProvider provider = parseProvider(providerName);
        String url = ExternalUrlGuard.normalize(provider == LlmProvider.CUSTOM ? baseUrl : provider.defaultBaseUrl());
        String cleanKey = requireText(apiKey, "API 키를 입력해 주세요").trim();
        String cleanModel = requireText(model, "모델을 입력해 주세요").trim();
        if (cleanModel.length() > 120) throw new IllegalArgumentException("모델 이름이 너무 깁니다");

        String error = probe(url, cleanKey, cleanModel);
        if (error != null) throw new IllegalArgumentException(error);

        LlmCredential saved = credentials.save(LlmCredential.create(memberId, provider, url, cleanModel,
                cipher.encrypt(cleanKey), hint(cleanKey)));
        saved.verified();
        activateOnly(memberId, saved);
        return view(saved);
    }

    @Transactional
    public Map<String, Object> verify(Long memberId, Long id) {
        LlmCredential credential = owned(memberId, id);
        String error = probe(credential.getBaseUrl(), cipher.decrypt(credential.getApiKeyEncrypted()), credential.getModel());
        if (error == null) credential.verified(); else credential.failed(error);
        return view(credential);
    }

    @Transactional
    public Map<String, Object> update(Long memberId, Long id, Boolean active, String model) {
        LlmCredential credential = owned(memberId, id);
        if (model != null && !model.isBlank() && !model.trim().equals(credential.getModel())) {
            String error = probe(credential.getBaseUrl(), cipher.decrypt(credential.getApiKeyEncrypted()), model.trim());
            if (error != null) throw new IllegalArgumentException(error);
            credential.changeModel(model.trim());
            credential.verified();
        }
        if (Boolean.TRUE.equals(active)) activateOnly(memberId, credential);
        if (Boolean.FALSE.equals(active)) credential.setActive(false);
        return view(credential);
    }

    @Transactional
    public void delete(Long memberId, Long id) {
        credentials.delete(owned(memberId, id));
    }

    /** 지금 사용 중인 키. 없거나 복호화할 수 없으면 비어 있다(서버 키로 처리). */
    @Transactional(readOnly = true)
    public Optional<ActiveKey> activeKey(Long memberId) {
        if (memberId == null || !cipher.isEnabled()) return Optional.empty();
        return credentials.findFirstByMemberIdAndActiveTrue(memberId).flatMap(c -> {
            try {
                return Optional.of(new ActiveKey(c.getProvider().name().toLowerCase(), c.getBaseUrl(),
                        cipher.decrypt(c.getApiKeyEncrypted()), c.getModel()));
            } catch (RuntimeException e) {
                log.warn("사용자 AI 키 복호화 실패 | credentialId: {}", c.getId());
                return Optional.empty();
            }
        });
    }

    /** 아주 짧은 요청 하나로 키·주소·모델이 맞는지 본다. 문제가 있으면 사람이 읽을 이유를 돌려준다. */
    String probe(String baseUrl, String apiKey, String model) {
        try {
            webClient.post().uri(baseUrl + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    // 토큰 상한 이름이 제공자마다 달라(max_tokens / max_completion_tokens) 넣지 않는다. 짧은 답만 받는다.
                    .bodyValue(Map.of("model", model,
                            "messages", List.of(Map.of("role", "user", "content", "Reply with OK."))))
                    .retrieve().toBodilessEntity()
                    .block(Duration.ofSeconds(20));
            return null;
        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) return "키가 올바르지 않거나 권한이 없습니다";
            if (status == 404) return "모델 이름이나 주소를 찾을 수 없습니다";
            if (status == 429) return "제공자 사용 한도에 걸렸습니다. 요금제나 잔액을 확인해 주세요";
            if (status == 400) return "요청을 받지 않았습니다. 모델 이름을 확인해 주세요";
            return "AI 서비스가 응답하지 않았습니다 (" + status + ")";
        } catch (RuntimeException e) {
            log.debug("AI 키 확인 실패: {}", e.getMessage());
            return "AI 서비스에 연결하지 못했습니다";
        }
    }

    private void activateOnly(Long memberId, LlmCredential target) {
        credentials.findByMemberIdOrderByCreatedAtAsc(memberId).forEach(c -> c.setActive(c.getId().equals(target.getId())));
        target.setActive(true);
    }

    private LlmCredential owned(Long memberId, Long id) {
        return credentials.findByIdAndMemberId(id, memberId)
                .orElseThrow(() -> new IllegalArgumentException("AI 키를 찾을 수 없습니다"));
    }

    private static LlmProvider parseProvider(String name) {
        try {
            return LlmProvider.valueOf(requireText(name, "제공자를 골라 주세요").trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("지원하지 않는 제공자입니다");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value;
    }

    static String hint(String key) {
        String tail = key.length() <= 4 ? key : key.substring(key.length() - 4);
        String head = key.length() > 8 && key.contains("-") ? key.substring(0, key.indexOf('-') + 1) : "";
        return head + "…" + tail;
    }

    private static Map<String, Object> view(LlmCredential c) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", c.getId());
        view.put("provider", c.getProvider().name());
        view.put("baseUrl", c.getBaseUrl());
        view.put("model", c.getModel());
        view.put("keyHint", c.getKeyHint());
        view.put("active", c.isActive());
        view.put("lastVerifiedAt", c.getLastVerifiedAt());
        view.put("lastError", c.getLastError());
        view.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt() : Instant.now());
        return view;
    }
}
