package com.smarttrip.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class TraceIdFilter implements Filter {

    private static final String TRACE_ID = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            HttpServletRequest req = (HttpServletRequest) request;
            String traceId = req.getHeader("X-Trace-Id");
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString().replace("-", "");
            }
            // 存入 Logback MDC
            MDC.put(TRACE_ID, traceId);
            // 回写给客户端响应头
            ((HttpServletResponse) response).setHeader("X-Trace-Id", traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID); // 清理，防止 Tomcat 线程复用污染
        }
    }
}