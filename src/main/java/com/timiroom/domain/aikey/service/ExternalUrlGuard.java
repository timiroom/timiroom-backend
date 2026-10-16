package com.timiroom.domain.aikey.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * 사용자가 넣은 AI 주소를 서버가 대신 부르기 전에 확인한다.
 * 내부망 주소를 넣어 서버 안쪽을 찌르는 것(SSRF)을 막기 위해 https와 공인 주소만 허용한다.
 */
public final class ExternalUrlGuard {

    private ExternalUrlGuard() {}

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("AI 주소를 입력해 주세요");
        String value = raw.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("AI 주소 형식이 올바르지 않습니다");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("AI 주소는 https로 시작해야 합니다");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("AI 주소에는 계정 정보나 ?, # 뒤 내용을 넣을 수 없습니다");
        }
        requirePublicHost(uri.getHost());
        return value;
    }

    static void requirePublicHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("AI 주소의 서버를 찾을 수 없습니다");
        }
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress() || isUniqueLocalV6(address)
                    || isCarrierGradeNat(address)) {
                throw new IllegalArgumentException("내부망 주소는 AI 주소로 쓸 수 없습니다");
            }
        }
    }

    private static boolean isUniqueLocalV6(InetAddress address) {
        byte[] b = address.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] b = address.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
    }
}
