package com.timiroom.infra.portone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PortOne V2 REST 클라이언트.
 * 인증: Authorization: PortOne {API_SECRET}
 * 문서: https://developers.portone.io (빌링키 결제 POST /payments/{paymentId}/billing-key)
 */
@Slf4j
@Component
@EnableConfigurationProperties(PortOneProperties.class)
public class PortOneClient {

    /** PortOne 요청 실패. message는 사용자에게 보여 줄 수 있는 한국어 설명이다. */
    public static class PortOneException extends RuntimeException {
        public PortOneException(String message) {
            super(message);
        }
    }

    private final PortOneProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public PortOneClient(PortOneProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().baseUrl(properties.getApiBaseUrl()).build();
    }

    /** 빌링키 정보(발급 상태, 고객 id, 카드 정보) */
    public JsonNode getBillingKey(String billingKey) {
        return get("/billing-keys/" + encode(billingKey));
    }

    /** 결제 단건 조회(상태, 금액, 영수증 주소 등) */
    public JsonNode getPayment(String paymentId) {
        return get("/payments/" + encode(paymentId));
    }

    /** 빌링키로 바로 결제한다. 성공하면 PortOne 응답(payment.paidAt 등)을 돌려준다. */
    public JsonNode payWithBillingKey(String paymentId, String billingKey, String orderName, long amountKrw,
                                      String customerId, String customerEmail) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", customerId);
        if (customerEmail != null && !customerEmail.isBlank()) customer.put("email", customerEmail);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storeId", properties.getStoreId());
        body.put("billingKey", billingKey);
        body.put("orderName", orderName);
        body.put("customer", customer);
        body.put("amount", Map.of("total", amountKrw));
        body.put("currency", "KRW");
        return send(webClient.post().uri("/payments/" + encode(paymentId) + "/billing-key").bodyValue(body));
    }

    /** 빌링키 삭제(카드 변경·해지 정리). 실패해도 흐름은 계속한다. */
    public void deleteBillingKey(String billingKey) {
        try {
            send(webClient.delete().uri("/billing-keys/" + encode(billingKey)));
        } catch (PortOneException e) {
            log.warn("빌링키 삭제 실패: {}", e.getMessage());
        }
    }

    private JsonNode get(String path) {
        return send(webClient.get().uri(path));
    }

    private JsonNode send(WebClient.RequestHeadersSpec<?> spec) {
        if (!properties.isReady()) throw new PortOneException("결제 설정이 아직 준비되지 않았습니다");
        try {
            String body = spec.header(HttpHeaders.AUTHORIZATION, "PortOne " + properties.getApiSecret())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().bodyToMono(String.class).defaultIfEmpty("{}")
                    .block(Duration.ofSeconds(30));
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (WebClientResponseException e) {
            throw new PortOneException(describe(e));
        } catch (PortOneException e) {
            throw e;
        } catch (Exception e) {
            log.error("PortOne 요청 실패", e);
            throw new PortOneException("결제 서버에 연결하지 못했습니다");
        }
    }

    /** PortOne 오류 응답 {type, message}를 사람이 읽을 문장으로 */
    private String describe(WebClientResponseException e) {
        try {
            JsonNode error = objectMapper.readTree(e.getResponseBodyAsString(StandardCharsets.UTF_8));
            String message = error.path("message").asText("");
            String type = error.path("type").asText("");
            log.warn("PortOne 오류 {} {}: {}", e.getStatusCode().value(), type, message);
            if (!message.isBlank()) return message;
            if (!type.isBlank()) return "결제에 실패했습니다 (" + type + ")";
        } catch (Exception ignored) {
            // 본문이 JSON이 아니면 상태 코드로 안내한다
        }
        return "결제에 실패했습니다 (" + e.getStatusCode().value() + ")";
    }

    private static String encode(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }
}
