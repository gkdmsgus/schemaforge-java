package com.schemaforge.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

@Controller
public class SpaController {

    // API health check
    @GetMapping("/health")
    @ResponseBody
    public Map<String, Object> health() {
        return Map.of("status", "ok", "version", "2.0.0");
    }

    // SPA fallback — 모든 non-API GET 요청을 index.html로 포워딩
    @GetMapping(value = {
        "/", "/generate", "/sessions", "/favorites",
        "/auth/**"
    })
    public String spa() {
        return "forward:/index.html";
    }
}
