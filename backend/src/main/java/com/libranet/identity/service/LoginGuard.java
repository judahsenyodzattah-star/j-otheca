package com.libranet.identity.service;

import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.identity.model.User;
import com.libranet.identity.repo.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable failed-login lockout (Phase 8). Runs REQUIRES_NEW: the caller immediately throws
 * after recording a failure, and a shared transaction would roll the counter back — opening
 * an infinite-retry hole. Locking is deliberate here and survives rollbacks AND restarts.
 */
@Service
public class LoginGuard {

    private final UserRepository users;
    private final AuditService audit;
    private final int maxAttempts;
    private final long lockoutMinutes;

    public LoginGuard(UserRepository users, AuditService audit,
                      @Value("${app.security.login-max-attempts:10}") int maxAttempts,
                      @Value("${app.security.login-lockout-minutes:15}") long lockoutMinutes) {
        this.users = users;
        this.audit = audit;
        this.maxAttempts = maxAttempts;
        this.lockoutMinutes = lockoutMinutes;
    }

    public boolean isLocked(User user) {
        return user.getLockedUntil() != null && Instant.now().isBefore(user.getLockedUntil());
    }

    public long lockedRetryAfterSeconds(User user) {
        return Math.max(1, user.getLockedUntil().getEpochSecond() - Instant.now().getEpochSecond());
    }

    /** Records a failed password/MFA attempt; locks the account when the threshold is crossed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(User user) {
        User managed = users.findById(user.getId()).orElseThrow();
        // an expired lock clears automatically on the next failure
        if (managed.getLockedUntil() != null && Instant.now().isAfter(managed.getLockedUntil())) {
            managed.setFailedAttempts(0);
            managed.setLockedUntil(null);
        }
        managed.setFailedAttempts(managed.getFailedAttempts() + 1);
        if (managed.getFailedAttempts() >= maxAttempts) {
            managed.setLockedUntil(Instant.now().plus(lockoutMinutes, ChronoUnit.MINUTES));
            managed.setFailedAttempts(0);
            users.save(managed);
            audit.log(AuditAction.ACCOUNT_LOCKED, managed.getId(), managed.getEmail(),
                    "User", managed.getId(), Map.of("minutes", lockoutMinutes));
            return;
        }
        users.save(managed);
    }

    /** Clears counters on a fully successful authentication. */
    public void reset(User user) {
        if (user.getFailedAttempts() > 0 || user.getLockedUntil() != null) {
            user.setFailedAttempts(0);
            user.setLockedUntil(null);
            users.save(user);
        }
    }
}
