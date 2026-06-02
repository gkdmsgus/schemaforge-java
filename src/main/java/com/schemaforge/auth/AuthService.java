package com.schemaforge.auth;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
public class AuthService {

    private final WebClient supabaseClient;

    public AuthService(Dotenv dotenv) {
        String url     = resolve(dotenv, "SUPABASE_URL");
        String anonKey = resolve(dotenv, "SUPABASE_ANON_KEY");

        this.supabaseClient = WebClient.builder()
                .baseUrl(url)
                .defaultHeader("apikey", anonKey)
                .defaultHeader("Content-Type", "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }

    public AuthResponse register(String email, String password) {
        JsonNode res = supabaseClient.post()
                .uri("/auth/v1/signup")
                .bodyValue(Map.of("email", email, "password", password))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        return toAuthResponse(res);
    }

    public AuthResponse login(String email, String password) {
        JsonNode res = supabaseClient.post()
                .uri("/auth/v1/token?grant_type=password")
                .bodyValue(Map.of("email", email, "password", password))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        return toAuthResponse(res);
    }

    // Verify token and return user id + email
    public AuthUser verify(String token) {
        JsonNode res = supabaseClient.get()
                .uri("/auth/v1/user")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (res == null || res.has("error")) return null;
        String id    = res.path("id").asText(null);
        String email = res.path("email").asText(null);
        if (id == null) return null;
        return new AuthUser(id, email);
    }

    private String resolve(Dotenv dotenv, String key) {
        String val = dotenv.get(key, null);
        if (val != null && !val.isBlank()) return val;
        val = System.getenv(key);
        return val != null ? val : "";
    }

    private AuthResponse toAuthResponse(JsonNode node) {
        if (node == null) throw new RuntimeException("인증 서버 응답 없음");
        if (node.has("error_description")) throw new RuntimeException(node.path("error_description").asText());
        if (node.has("msg")) throw new RuntimeException(node.path("msg").asText());

        String token = node.path("access_token").asText(null);
        JsonNode userNode = node.has("user") ? node.path("user") : node;
        String id    = userNode.path("id").asText(null);
        String email = userNode.path("email").asText(null);

        if (id == null) throw new RuntimeException("사용자 정보를 가져올 수 없습니다.");
        return new AuthResponse(new AuthUser(id, email), token);
    }
}
