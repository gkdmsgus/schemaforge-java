package com.schemaforge.config;

import com.schemaforge.auth.AuthService;
import com.schemaforge.auth.AuthUser;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

@Component
@Order(1)
public class AuthFilter implements Filter {

    private static final Set<String> PROTECTED = Set.of(
        "/generate", "/clarify", "/plan", "/chat_edit", "/test_code",
        "/sessions", "/favorites",
        "/generate_pcb", "/generate_gerber"
    );

    private final AuthService authService;

    public AuthFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  http  = (HttpServletRequest)  req;
        HttpServletResponse hres  = (HttpServletResponse) res;
        String path   = http.getRequestURI();
        String header = http.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                AuthUser user = authService.verify(token);
                if (user != null) http.setAttribute("authUser", user);
            } catch (Exception ignored) {}
        }

        // 보호된 경로에 인증 없이 접근하면 401 반환
        boolean isProtected = PROTECTED.stream().anyMatch(path::startsWith);
        if (isProtected && http.getAttribute("authUser") == null) {
            hres.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            hres.setContentType("application/json;charset=UTF-8");
            hres.getWriter().write("{\"error\":\"로그인이 필요합니다.\"}");
            return;
        }

        chain.doFilter(req, res);
    }
}
