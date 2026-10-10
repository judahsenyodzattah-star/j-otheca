package com.jotheca.identity.repo;

import com.jotheca.identity.model.VerificationToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationTokenRepository extends JpaRepository<VerificationToken, String> {
    Optional<VerificationToken> findByTokenHash(String tokenHash);
}
