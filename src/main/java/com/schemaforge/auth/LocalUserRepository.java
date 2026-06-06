package com.schemaforge.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface LocalUserRepository extends JpaRepository<LocalUser, UUID> {
    Optional<LocalUser> findByEmail(String email);
}
