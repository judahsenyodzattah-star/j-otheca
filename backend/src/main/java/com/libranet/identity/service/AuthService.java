package com.libranet.identity.service;

import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.CodeGenerator;
import com.libranet.identity.dto.ForgotPasswordRequest;
import com.libranet.identity.dto.LoginRequest;
import com.libranet.identity.dto.RefreshRequest;
import com.libranet.identity.dto.RegisterRequest;
import com.libranet.identity.dto.RegisterResponse;
import com.libranet.identity.dto.ResetPasswordRequest;
import com.libranet.identity.dto.SecurityDtos.MfaChallengeResponse;
import com.libranet.identity.dto.SecurityDtos.MfaVerifyRequest;
import com.libranet.identity.dto.TokenResponse;
import com.libranet.identity.dto.UserDto;
import com.libranet.identity.dto.VerifyEmailRequest;
import com.libranet.identity.model.AuthSession;
import com.libranet.identity.model.User;
import com.libranet.identity.model.UserStatus;
import com.libranet.identity.model.VerificationToken;
import com.libranet.identity.model.VerificationToken.Type;
import com.libranet.identity.repo.AuthSessionRepository;
import com.libranet.identity.repo.UserRepository;
import com.libranet.identity.repo.VerificationTokenRepository;
import com.libranet.identity.security.JwtService;
import com.libranet.identity.security.Totp;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final AuthSessionRepository sessions;
    private final VerificationTokenRepository verificationTokens;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final MailService mail;
    private final SessionRevoker sessionRevoker;
    private final LoginGuard loginGuard;
    private final Environment environment;
    private final long refreshTtlDays;

    public AuthService(UserRepository users, AuthSessionRepository sessions,
                       VerificationTokenRepository verificationTokens, PasswordEncoder encoder,
                       JwtService jwt, AuditService audit, MailService mail, SessionRevoker sessionRevoker,
                       LoginGuard loginGuard, Environment environment,
                       @Value("${app.refresh-token-ttl-days:14}") long refreshTtlDays) {
        this.users = users;
        this.sessions = sessions;
        this.verificationTokens = verificationTokens;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
        this.mail = mail;
        this.sessionRevoker = sessionRevoker;
        this.loginGuard = loginGuard;
        this.environment = environment;
        this.refreshTtlDays = refreshTtlDays;
    }

    // ---------- registration & verification ----------

    @Transactional
    public RegisterResponse register(RegisterRequest req) {
        String email = normalize(req.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }
        User user = new User();
        user.setEmail(email);
        user.setFullName(req.fullName().trim());
        user.setPhone(req.phone());
        user.setPasswordHash(encoder.encode(req.password()));
        users.save(user);

        String rawToken = issueVerificationToken(user.getId(), Type.EMAIL_VERIFY, 24 * 60);
        mail.sendEmailVerification(email, rawToken);
        audit.log(AuditAction.REGISTER, user.getId(), email, "User", user.getId(), Map.of());

        boolean dev = Arrays.asList(environment.getActiveProfiles()).contains("dev");
        return new RegisterResponse(UserDto.from(user), dev ? rawToken : null);
    }

    @Transactional
    public void verifyEmail(VerifyEmailRequest req) {
        VerificationToken token = usableToken(req.token(), Type.EMAIL_VERIFY);
        User user = users.findById(token.getUserId())
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "Account not found"));
        user.setEmailVerifiedAt(Instant.now());
        token.setUsedAt(Instant.now());
        audit.log(AuditAction.EMAIL_VERIFIED, user.getId(), user.getEmail(), "User", user.getId(), Map.of());
    }

    // ---------- login / refresh / logout ----------

    /**
     * Password + optional TOTP second factor, with durable lockout (Phase 8).
     * Returns {@link TokenResponse} on full success, or {@link MfaChallengeResponse} when the
     * account requires a TOTP code — exchange it at {@code POST /api/v1/auth/mfa/verify}.
     */
    @Transactional
    public Object login(LoginRequest req) {
        String email = normalize(req.email());
        User user = users.findByEmail(email).orElse(null);
        if (user != null && loginGuard.isLocked(user)) {
            audit.log(AuditAction.LOGIN_FAILED, user.getId(), email, "User", user.getId(),
                    Map.of("reason", "account_locked"));
            throw new ApiException(org.springframework.http.HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                    "Too many failed attempts — this account is temporarily locked.",
                    Map.of("retryAfterSeconds", loginGuard.lockedRetryAfterSeconds(user)));
        }
        if (user == null || !encoder.matches(req.password(), user.getPasswordHash())) {
            // actor identified by supplied email only; never reveal which part failed (enumeration resistance)
            if (user != null) {
                loginGuard.recordFailure(user);   // dedicated tx — survives our rollback
            }
            audit.log(AuditAction.LOGIN_FAILED, null, email, "User",
                    user != null ? user.getId() : null, Map.of("reason", "bad_credentials"));
            throw ApiException.unauthorized("BAD_CREDENTIALS", "Invalid email or password");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            audit.log(AuditAction.LOGIN_FAILED, user.getId(), email, "User", user.getId(), Map.of("reason", "account_not_active"));
            throw ApiException.unauthorized("ACCOUNT_INACTIVE", "This account is not active");
        }
        loginGuard.reset(user);
        if (user.isMfaEnabled()) {
            // No tokens yet — issue a 5-minute challenge. The audit trail records LOGIN_SUCCESS
            // only when the second factor verifies (see mfaVerify).
            return MfaChallengeResponse.of(jwt.createMfaChallenge(user.getId()));
        }
        audit.log(AuditAction.LOGIN_SUCCESS, user.getId(), email, "User", user.getId(), Map.of());
        return issueTokens(user, UUID.randomUUID().toString(), null);
    }

    /** Exchange an MFA challenge token + TOTP code for real tokens. */
    @Transactional
    public TokenResponse mfaVerify(MfaVerifyRequest req) {
        String userId = jwt.parseMfaChallenge(req.mfaToken());
        User user = userId == null ? null : users.findById(userId).orElse(null);
        if (user == null || !user.isMfaEnabled() || user.getMfaSecret() == null) {
            throw ApiException.unauthorized("MFA_CHALLENGE_INVALID", "MFA challenge expired or invalid. Sign in again.");
        }
        if (loginGuard.isLocked(user)) {
            throw new ApiException(org.springframework.http.HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                    "This account is temporarily locked.",
                    Map.of("retryAfterSeconds", loginGuard.lockedRetryAfterSeconds(user)));
        }
        if (!Totp.verify(user.getMfaSecret(), req.code())) {
            loginGuard.recordFailure(user);   // the same lockout also guards the 6-digit factor
            audit.log(AuditAction.MFA_VERIFY_FAILED, user.getId(), user.getEmail(),
                    "User", user.getId(), Map.of("stage", "login"));
            throw ApiException.unauthorized("MFA_CODE_INVALID", "That code is not valid.");
        }
        loginGuard.reset(user);
        audit.log(AuditAction.LOGIN_SUCCESS, user.getId(), user.getEmail(), "User", user.getId(),
                Map.of("mfa", true));
        return issueTokens(user, UUID.randomUUID().toString(), null);
    }

    /**
     * Refresh-token rotation with reuse detection (spec §36). Replaying an already-rotated
     * token is treated as theft: the entire token family is revoked and a security event is audited.
     */
    @Transactional
    public TokenResponse refresh(RefreshRequest req) {
        AuthSession session = sessions.findByTokenHash(sha256(req.refreshToken()))
                .orElseThrow(() -> ApiException.unauthorized("INVALID_REFRESH", "Session not found or expired"));

        if (session.getRevokedAt() != null) {
            sessionRevoker.revokeFamily(session.getFamily());
            audit.log(AuditAction.TOKEN_REUSE_DETECTED, session.getUserId(), null,
                    "AuthSession", session.getId(), Map.of("family", session.getFamily()));
            throw ApiException.unauthorized("TOKEN_REUSE_DETECTED", "Session revoked for your security. Please sign in again.");
        }
        if (!session.isActive(Instant.now())) {
            throw ApiException.unauthorized("INVALID_REFRESH", "Session expired. Please sign in again.");
        }
        User user = users.findById(session.getUserId())
                .orElseThrow(() -> ApiException.unauthorized("INVALID_REFRESH", "Session owner not found"));

        TokenResponse rotated = issueTokens(user, session.getFamily(), session);
        audit.log(AuditAction.TOKEN_REFRESH, user.getId(), user.getEmail(), "AuthSession", session.getId(), Map.of());
        return rotated;
    }

    @Transactional
    public void logout(String refreshToken) {
        sessions.findByTokenHash(sha256(refreshToken)).ifPresent(session -> {
            if (session.getRevokedAt() == null) {
                session.setRevokedAt(Instant.now());
                sessions.save(session);
                users.findById(session.getUserId()).ifPresent(u ->
                        audit.log(AuditAction.LOGOUT, u.getId(), u.getEmail(), "User", u.getId(), Map.of()));
            }
        });
    }

    // ---------- password reset ----------

    @Transactional
    public void forgotPassword(ForgotPasswordRequest req) {
        String email = normalize(req.email());
        users.findByEmail(email).ifPresent(user -> {
            String rawToken = issueVerificationToken(user.getId(), Type.PASSWORD_RESET, 60);
            mail.sendPasswordReset(email, rawToken);
            audit.log(AuditAction.PASSWORD_RESET_REQUESTED, user.getId(), email, "User", user.getId(), Map.of());
        });
        // always succeed — never disclose whether the account exists
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest req) {
        VerificationToken token = usableToken(req.token(), Type.PASSWORD_RESET);
        User user = users.findById(token.getUserId())
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "Account not found"));
        user.setPasswordHash(encoder.encode(req.newPassword()));
        token.setUsedAt(Instant.now());
        sessionRevoker.revokeAllForUser(user.getId()); // force re-login on all devices (spec §36)
        audit.log(AuditAction.PASSWORD_RESET, user.getId(), user.getEmail(), "User", user.getId(), Map.of());
    }

    // ---------- helpers ----------

    private TokenResponse issueTokens(User user, String family, AuthSession replacing) {
        String rawRefresh = newOpaqueToken();
        AuthSession session = new AuthSession();
        session.setUserId(user.getId());
        session.setTokenHash(sha256(rawRefresh));
        session.setFamily(family);
        session.setExpiresAt(Instant.now().plus(refreshTtlDays, ChronoUnit.DAYS));
        session.setLastUsedAt(Instant.now());
        // device/IP from the live request; on rotation, inherit what the replaced session knew
        session.setDeviceInfo(DeviceLabel.fromUserAgent(currentHeader("User-Agent", replacing == null ? null : replacing.getDeviceInfo())));
        session.setIp(currentIp(replacing == null ? null : replacing.getIp()));
        sessions.save(session);

        if (replacing != null) {
            replacing.setRevokedAt(Instant.now());
            replacing.setLastUsedAt(Instant.now());
            replacing.setReplacedBy(session.getId());
            sessions.save(replacing);
        }

        String access = jwt.createAccessToken(user.getId(), user.getEmail(), user.getGlobalRole().name(), session.getId());
        return new TokenResponse(access, "Bearer", jwt.accessTtlSeconds(), rawRefresh, UserDto.from(user));
    }

    private static String currentHeader(String name, String fallback) {
        try {
            var attrs = (org.springframework.web.context.request.ServletRequestAttributes)
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            String value = attrs == null ? null : attrs.getRequest().getHeader(name);
            return value != null && !value.isBlank() ? value.substring(0, Math.min(value.length(), 300)) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String currentIp(String fallback) {
        try {
            var attrs = (org.springframework.web.context.request.ServletRequestAttributes)
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs == null) return fallback;
            String forwarded = attrs.getRequest().getHeader("X-Forwarded-For");
            return forwarded != null ? forwarded.split(",")[0].trim() : attrs.getRequest().getRemoteAddr();
        } catch (Exception e) {
            return fallback;
        }
    }

    private VerificationToken usableToken(String rawToken, Type expectedType) {
        VerificationToken token = verificationTokens.findByTokenHash(sha256(rawToken))
                .filter(t -> t.getType() == expectedType)
                .orElseThrow(() -> ApiException.badRequest("INVALID_TOKEN", "This link is invalid"));
        if (!token.usable(Instant.now())) {
            throw ApiException.badRequest("TOKEN_EXPIRED_OR_USED", "This link has expired or was already used");
        }
        return token;
    }

    private String issueVerificationToken(String userId, Type type, int ttlMinutes) {
        String raw = CodeGenerator.randomToken(40);
        VerificationToken token = new VerificationToken();
        token.setUserId(userId);
        token.setType(type);
        token.setTokenHash(sha256(raw));
        token.setExpiresAt(Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES));
        verificationTokens.save(token);
        return raw;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String newOpaqueToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
