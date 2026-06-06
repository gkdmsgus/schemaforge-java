package com.schemaforge.auth;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private final boolean supabaseEnabled;
    private final WebClient supabaseClient;

    // local dev auth (when Supabase not configured)
    private final LocalUserRepository localUsers;
    private final ConcurrentHashMap<String, AuthUser> tokenStore = new ConcurrentHashMap<>();

    public AuthService(Dotenv dotenv, LocalUserRepository localUsers) {
        this.localUsers = localUsers;

        String url     = resolve(dotenv, "SUPABASE_URL");
        String anonKey = resolve(dotenv, "SUPABASE_ANON_KEY");
        this.supabaseEnabled = !url.isBlank() && !anonKey.isBlank();

        this.supabaseClient = WebClient.builder()
                .baseUrl(supabaseEnabled ? url : "http://localhost")
                .defaultHeader("apikey", anonKey)
                .defaultHeader("Content-Type", "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }

    public AuthResponse register(String email, String password) {
        if (!supabaseEnabled) return localRegister(email, password);

        JsonNode res = supabaseClient.post()
                .uri("/auth/v1/signup")
                .bodyValue(Map.of("email", email, "password", password))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        return toAuthResponse(res);
    }

    public AuthResponse login(String email, String password) {
        if (!supabaseEnabled) return localLogin(email, password);

        JsonNode res = supabaseClient.post()
                .uri("/auth/v1/token?grant_type=password")
                .bodyValue(Map.of("email", email, "password", password))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        return toAuthResponse(res);
    }

    public AuthUser verify(String token) {
        if (!supabaseEnabled) return tokenStore.get(token);

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

    // ── Local auth (dev only) ─────────────────────────────────────

    private AuthResponse localRegister(String email, String password) {
        if (localUsers.findByEmail(email).isPresent())
            throw new RuntimeException("이미 사용 중인 이메일입니다.");

        LocalUser u = new LocalUser();
        u.setEmail(email);
        u.setPasswordHash(hash(password));
        localUsers.save(u);

        String token = issueToken(new AuthUser(u.getId().toString(), email));
        return new AuthResponse(new AuthUser(u.getId().toString(), email), token);
    }

    private AuthResponse localLogin(String email, String password) {
        LocalUser u = localUsers.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("이메일 또는 비밀번호가 올바르지 않습니다."));
        if (!u.getPasswordHash().equals(hash(password)))
            throw new RuntimeException("이메일 또는 비밀번호가 올바르지 않습니다.");

        String token = issueToken(new AuthUser(u.getId().toString(), email));
        return new AuthResponse(new AuthUser(u.getId().toString(), email), token);
    }

    private String issueToken(AuthUser user) {
        String token = UUID.randomUUID().toString();
        tokenStore.put(token, user);
        return token;
    }

    private static String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────

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
