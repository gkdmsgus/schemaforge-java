package com.schemaforge.db.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "sessions")
@Getter @Setter @NoArgsConstructor
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(columnDefinition = "TEXT")
    private String code;

    @Column(columnDefinition = "TEXT")
    private String guide;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Object graph;

    private String filename;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
