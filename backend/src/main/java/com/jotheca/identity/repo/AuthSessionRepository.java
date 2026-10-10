package com.jotheca.identity.repo;

import com.jotheca.identity.model.AuthSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {

    Optional<AuthSession> findByTokenHash(String tokenHash);

    List<AuthSession> findByUserIdAndRevokedAtIsNull(String userId);

    List<AuthSession> findByFamily(String family);

    @Modifying
    @Query("UPDATE AuthSession s SET s.revokedAt = :now WHERE s.family = :family AND s.revokedAt IS NULL")
    int revokeFamily(@Param("family") String family, @Param("now") java.time.Instant now);

    @Modifying
    @Query("UPDATE AuthSession s SET s.revokedAt = :now WHERE s.userId = :userId AND s.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") String userId, @Param("now") java.time.Instant now);
}
