package com.schemaforge.db.repository;

import com.schemaforge.db.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {
    List<Session> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Optional<Session> findByIdAndUserId(UUID id, UUID userId);
}
