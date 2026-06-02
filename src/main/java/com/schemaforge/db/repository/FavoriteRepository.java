package com.schemaforge.db.repository;

import com.schemaforge.db.entity.Favorite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FavoriteRepository extends JpaRepository<Favorite, UUID> {
    List<Favorite> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<Favorite> findByIdAndUserId(UUID id, UUID userId);
    Optional<Favorite> findByUserIdAndSessionId(UUID userId, UUID sessionId);
}
