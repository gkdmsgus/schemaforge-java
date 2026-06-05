package com.schemaforge.config;

import com.schemaforge.auth.AuthUser;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 분당 10회 / 유저 rate limiting (메모리 기반, 단일 인스턴스용)
 * 보호 대상: /generate, /clarify, /plan
 */
@Component
@Order(2)
public class RateLimitFilter implements Filter {

    private static final int MAX_PER_MINUTE = 10;
    private static final long WINDOW_MS = 60_000L;

    private record Window(AtomicInteger count, long resetAt) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  http = (HttpServletRequest)  req;
        HttpServletResponse hres = (HttpServletResponse) res;
        String path = http.getRequestURI();

        if (path.startsWith("/generate") || path.startsWith("/clarify") || path.startsWith("/plan")) {
            AuthUser user = (AuthUser) http.getAttribute("authUser");
            String key = user != null ? "u:" + user.getId() : "ip:" + http.getRemoteAddr();

            long now = System.currentTimeMillis();
            Window w = windows.compute(key, (k, existing) -> {
                if (existing == null || now >= existing.resetAt()) {
                    return new Window(new AtomicInteger(0), now + WINDOW_MS);
                }
                return existing;
            });

            int count = w.count().incrementAndGet();
            hres.setHeader("X-RateLimit-Limit", String.valueOf(MAX_PER_MINUTE));
            hres.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, MAX_PER_MINUTE - count)));

            if (count > MAX_PER_MINUTE) {
                hres.setStatus(429);
                hres.setContentType("application/json;charset=UTF-8");
                hres.getWriter().write("{\"error\":\"요청이 너무 많습니다. 잠시 후 다시 시도해주세요.\"}");
                return;
            }
        }

        chain.doFilter(req, res);
    }
}
