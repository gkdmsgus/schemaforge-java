package com.schemaforge.auth;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Getter @NoArgsConstructor
    static class LoginRequest {
        private String email;
        private String password;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody LoginRequest req) {
        if (blank(req.getEmail()) || blank(req.getPassword()))
            return ResponseEntity.badRequest().body(Map.of("error", "이메일과 비밀번호를 입력하세요."));
        try {
            return ResponseEntity.ok(authService.register(req.getEmail(), req.getPassword()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (blank(req.getEmail()) || blank(req.getPassword()))
            return ResponseEntity.badRequest().body(Map.of("error", "이메일과 비밀번호를 입력하세요."));
        try {
            return ResponseEntity.ok(authService.login(req.getEmail(), req.getPassword()));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "이메일 또는 비밀번호가 올바르지 않습니다."));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        AuthUser user = (AuthUser) request.getAttribute("authUser");
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        return ResponseEntity.ok(Map.of("user", user));
    }

    private boolean blank(String s) { return s == null || s.isBlank(); }
}
