package com.jotheca.identity.service;

import com.jotheca.audit.AuditService;
import com.jotheca.audit.model.AuditAction;
import com.jotheca.common.error.ApiException;
import com.jotheca.common.web.SecurityUsers;
import com.jotheca.identity.dto.SecurityDtos.MfaEnrollResponse;
import com.jotheca.identity.dto.SecurityDtos.MfaStatusResponse;
import com.jotheca.identity.model.User;
import com.jotheca.identity.repo.UserRepository;
import com.jotheca.identity.security.Totp;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TOTP MFA enrolment/enable/disable (Phase 8). The login-time challenge exchange lives in
 * {@link AuthService} (it issues the session). Secrets are stored on the user row and are
 * NEVER written to the audit log or any DTO beyond the one-time enrolment response.
 */
@Service
public class MfaService {

    private static final String ISSUER = "J’otheca";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public MfaService(UserRepository users, PasswordEncoder encoder, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public MfaStatusResponse status() {
        return new MfaStatusResponse(currentUser().isMfaEnabled());
    }

    /** Step 1: generate (or rotate a not-yet-enabled) secret. Enabling requires step 2. */
    @Transactional
    public MfaEnrollResponse enroll() {
        User user = currentUser();
        if (user.isMfaEnabled()) {
            throw ApiException.conflict("MFA_ALREADY_ENABLED",
                    "Authenticator already enabled. Disable it before re-enrolling.");
        }
        String secret = Totp.generateSecret();
        user.setMfaSecret(secret);
        user.setMfaEnabled(false);       // not active until a valid code proves possession
        users.save(user);
        audit.log(AuditAction.MFA_ENROLLED, user.getId(), user.getEmail(), "User", user.getId(), Map.of());
        return new MfaEnrollResponse(secret, Totp.otpauthUri(ISSUER, user.getEmail(), secret));
    }

    /** Step 2: prove possession of the authenticator with a valid code → MFA on. */
    @Transactional
    public MfaStatusResponse enable(String code) {
        User user = currentUser();
        if (user.isMfaEnabled()) {
            throw ApiException.conflict("MFA_ALREADY_ENABLED", "Authenticator already enabled.");
        }
        if (user.getMfaSecret() == null) {
            throw ApiException.badRequest("MFA_NOT_ENROLLED", "Enroll first to get a secret.");
        }
        if (!Totp.verify(user.getMfaSecret(), code)) {
            audit.log(AuditAction.MFA_VERIFY_FAILED, user.getId(), user.getEmail(),
                    "User", user.getId(), Map.of("stage", "enable"));
            throw ApiException.badRequest("MFA_CODE_INVALID", "That code is not valid. Check your authenticator app.");
        }
        user.setMfaEnabled(true);
        users.save(user);
        audit.log(AuditAction.MFA_ENABLED, user.getId(), user.getEmail(), "User", user.getId(), Map.of());
        return new MfaStatusResponse(true);
    }

    /** Disable requires BOTH the password and a current code — a stolen session alone cannot. */
    @Transactional
    public MfaStatusResponse disable(String password, String code) {
        User user = currentUser();
        if (!user.isMfaEnabled()) {
            return new MfaStatusResponse(false);
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            audit.log(AuditAction.MFA_VERIFY_FAILED, user.getId(), user.getEmail(),
                    "User", user.getId(), Map.of("stage", "disable_password"));
            throw ApiException.unauthorized("BAD_CREDENTIALS", "Invalid password");
        }
        if (!Totp.verify(user.getMfaSecret(), code)) {
            audit.log(AuditAction.MFA_VERIFY_FAILED, user.getId(), user.getEmail(),
                    "User", user.getId(), Map.of("stage", "disable_code"));
            throw ApiException.badRequest("MFA_CODE_INVALID", "That code is not valid.");
        }
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        users.save(user);
        audit.log(AuditAction.MFA_DISABLED, user.getId(), user.getEmail(), "User", user.getId(), Map.of());
        return new MfaStatusResponse(false);
    }

    private User currentUser() {
        return users.findById(SecurityUsers.current().id())
                .orElseThrow(() -> ApiException.unauthorized("USER_NOT_FOUND", "User not found"));
    }
}
