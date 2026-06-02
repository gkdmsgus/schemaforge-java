package com.schemaforge.db.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "favorites", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "session_id"}))
@Getter @Setter @NoArgsConstructor
public class Favorite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
