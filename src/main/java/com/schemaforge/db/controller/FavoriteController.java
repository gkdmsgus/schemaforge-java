package com.schemaforge.db.controller;

import com.schemaforge.auth.AuthUser;
import com.schemaforge.db.entity.Favorite;
import com.schemaforge.db.entity.Session;
import com.schemaforge.db.repository.FavoriteRepository;
import com.schemaforge.db.repository.SessionRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/favorites")
@CrossOrigin(origins = "*")
public class FavoriteController {

    private final FavoriteRepository favorites;
    private final SessionRepository  sessions;

    public FavoriteController(FavoriteRepository favorites, SessionRepository sessions) {
        this.favorites = favorites;
        this.sessions  = sessions;
    }

    @GetMapping
    public ResponseEntity<?> getFavorites(HttpServletRequest req) {
        AuthUser user = authUser(req);
        if (user == null) return unauthorized();

        UUID userId = UUID.fromString(user.getId());
        List<Favorite> favList = favorites.findByUserIdOrderByCreatedAtDesc(userId);

        List<UUID> sessionIds = favList.stream().map(Favorite::getSessionId).toList();
        Map<UUID, Session> sessionMap = sessions.findAllById(sessionIds)
                .stream().collect(Collectors.toMap(Session::getId, s -> s));

        var enriched = favList.stream().map(f -> {
            Session s = sessionMap.get(f.getSessionId());
            return Map.of(
                "id",        f.getId().toString(),
                "sessionId", f.getSessionId().toString(),
                "prompt",    s != null ? s.getPrompt() : "",
                "graph",     s != null && s.getGraph() != null ? s.getGraph() : Map.of(),
                "filename",  s != null && s.getFilename() != null ? s.getFilename() : "",
                "createdAt", f.getCreatedAt() != null ? f.getCreatedAt().toString() : ""
            );
        }).toList();

        return ResponseEntity.ok(Map.of("favorites", enriched));
    }

    @Getter @NoArgsConstructor
    static class AddFavoriteRequest { private UUID session_id; }

    @PostMapping
    public ResponseEntity<?> addFavorite(@RequestBody AddFavoriteRequest body, HttpServletRequest req) {
        AuthUser user = authUser(req);
        if (user == null) return unauthorized();
        if (body.getSession_id() == null)
            return ResponseEntity.badRequest().body(Map.of("error", "session_id required"));

        UUID userId    = UUID.fromString(user.getId());
        UUID sessionId = body.getSession_id();

        Favorite fav = favorites.findByUserIdAndSessionId(userId, sessionId)
                .orElseGet(() -> {
                    Favorite f = new Favorite();
                    f.setUserId(userId);
                    f.setSessionId(sessionId);
                    return favorites.save(f);
                });

        return ResponseEntity.ok(Map.of("id", fav.getId()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteFavorite(@PathVariable UUID id, HttpServletRequest req) {
        AuthUser user = authUser(req);
        if (user == null) return unauthorized();
        favorites.findByIdAndUserId(id, UUID.fromString(user.getId()))
                .ifPresent(favorites::delete);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private AuthUser authUser(HttpServletRequest req) {
        return (AuthUser) req.getAttribute("authUser");
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
    }
}
