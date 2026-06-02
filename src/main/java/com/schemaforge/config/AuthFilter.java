package com.schemaforge.config;

import com.schemaforge.auth.AuthService;
import com.schemaforge.auth.AuthUser;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(1)
public class AuthFilter implements Filter {

    private final AuthService authService;

    public AuthFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest http = (HttpServletRequest) req;
        String header = http.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                AuthUser user = authService.verify(token);
                if (user != null) http.setAttribute("authUser", user);
            } catch (Exception ignored) {}
        }

        chain.doFilter(req, res);
    }
}
