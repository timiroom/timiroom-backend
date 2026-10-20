package com.timiroom.infra.portone;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** PortOne V2 설정. storeId·channelKey는 브라우저에도 내려가는 값, apiSecret·webhookSecret은 서버 전용이다. */
@Getter
@Setter
@ConfigurationProperties(prefix = "portone")
public class PortOneProperties {
    private boolean enabled;
    private String apiBaseUrl = "https://api.portone.io";
    private String storeId = "";
    private String channelKey = "";
    private String apiSecret = "";
    private String webhookSecret = "";

    public boolean isReady() {
        return enabled && notBlank(storeId) && notBlank(channelKey) && notBlank(apiSecret);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
