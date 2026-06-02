package com.schemaforge.db.controller;

import com.schemaforge.auth.AuthUser;
import com.schemaforge.db.entity.ChatMessage;
import com.schemaforge.db.entity.Session;
import com.schemaforge.db.repository.ChatMessageRepository;
import com.schemaforge.db.repository.SessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@CrossOrigin(origins = "*")
public class SessionController {

    private final SessionRepository sessions;
    private final ChatMessageRepository messages;

    public SessionController(SessionRepository sessions, ChatMessageRepository messages) {
        this.sessions = sessions;
        this.messages = messages;
    }

    // ── Sessions ──────────────────────────────────────────────────

    @GetMapping("/sessions")
    public ResponseEntity<?> getSessions(HttpServletRequest req) {
        AuthUser user = authUser(req);
        if (user == null) return unauthorized();
        List<Session> list = sessions.findByUserIdOrderByCreatedAtDesc(UUID.fromString(user.getId()));
        return ResponseEntity.ok(Map.of("sessions", list));
    }

    @Getter @NoArgsConstructor
    static class SaveSessionRequest {
        private String prompt;
        private String code;
        private String guide;
        private Object graph;
        private String filename;
    }

    @PostMapping("/sessions")
    public ResponseEntity<?> saveSession(@RequestBody SaveSessionRequest body, HttpServletRequest req) {
        if (body.getPrompt() == null || body.getPrompt().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "prompt required"));

        Session s = new Session();
        s.setPrompt(body.getPrompt());
        s.setCode(body.getCode());
        s.setGuide(body.getGuide());
        s.setGraph(body.getGraph());
        s.setFilename(body.getFilename());

        AuthUser user = authUser(req);
        if (user != null) s.setUserId(UUID.fromString(user.getId()));

        Session saved = sessions.save(s);
        return ResponseEntity.ok(Map.of("id", saved.getId()));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> deleteSession(@PathVariable UUID id, HttpServletRequest req) {
        AuthUser user = authUser(req);
        if (user == null) return unauthorized();
        sessions.findByIdAndUserId(id, UUID.fromString(user.getId()))
                .ifPresent(sessions::delete);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Chat Messages ─────────────────────────────────────────────

    @GetMapping("/sessions/{id}/messages")
    public ResponseEntity<?> getMessages(@PathVariable UUID id, HttpServletRequest req) {
        AuthUser user = authUser(req);
        if (user == null) return unauthorized();
        sessions.findByIdAndUserId(id, UUID.fromString(user.getId()))
                .orElseThrow(() -> new RuntimeException("Session not found"));
        return ResponseEntity.ok(Map.of("messages",
                messages.findBySessionIdOrderByCreatedAtAsc(id)));
    }

    @Getter @NoArgsConstructor
    static class SaveMessageRequest {
        private String role;
        private String content;
        private Object actions;
    }

    @PostMapping("/sessions/{id}/messages")
    public ResponseEntity<?> saveMessage(@PathVariable UUID id,
                                          @RequestBody SaveMessageRequest body,
                                          HttpServletRequest req) {
        if (body.getRole() == null || body.getContent() == null)
            return ResponseEntity.badRequest().body(Map.of("error", "role and content required"));

        ChatMessage m = new ChatMessage();
        m.setSessionId(id);
        m.setRole(body.getRole());
        m.setContent(body.getContent());
        m.setActions(body.getActions());
        messages.save(m);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── helpers ───────────────────────────────────────────────────

    private AuthUser authUser(HttpServletRequest req) {
        return (AuthUser) req.getAttribute("authUser");
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
    }
}
