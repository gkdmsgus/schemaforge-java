package com.schemaforge.db.controller;

import com.schemaforge.auth.AuthUser;
import com.schemaforge.db.entity.Favorite;
import com.schemaforge.db.repository.FavoriteRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/favorites")
@CrossOrigin(origins = "*")
public class FavoriteController {

    private final FavoriteRepository favorites;

    public FavoriteController(FavoriteRepository favorites) {
        this.favorites = favorites;
    }

    @GetMapping
    public ResponseEntity<?> getFavorites(HttpServletRequest req) {
        AuthUser user = authUser(req);
        if (user == null) return unauthorized();
        return ResponseEntity.ok(Map.of("favorites",
                favorites.findByUserIdOrderByCreatedAtDesc(UUID.fromString(user.getId()))));
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
