package com.rag.pipeline.common.logging;

import jakarta.servlet.*;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(1)  // 가장 먼저 실행되는 필터
public class MdcLoggingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        try {
            // 모든 요청에 고유 requestId 부여 (앞 8자리만 사용)
            MDC.put("requestId", UUID.randomUUID().toString().substring(0, 8));
            chain.doFilter(request, response);
        } finally {
            MDC.clear();  // 요청 끝나면 반드시 MDC 초기화 (메모리 누수 방지)
        }
    }
}