package com.jotheca.identity.service;

import com.jotheca.identity.repo.AuthSessionRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs in its own transaction: when token theft is detected we must revoke the compromised
 * family even though the calling flow immediately throws (which would roll a shared tx back).
 */
@Service
public class SessionRevoker {

    private final AuthSessionRepository sessions;

    public SessionRevoker(AuthSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(String family) {
        sessions.revokeFamily(family, Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllForUser(String userId) {
        sessions.revokeAllForUser(userId, Instant.now());
    }
}
