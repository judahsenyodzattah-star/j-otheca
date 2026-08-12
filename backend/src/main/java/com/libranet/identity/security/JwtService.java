package com.libranet.identity.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Stateless access-token service. Refresh tokens are NOT JWTs — they are opaque,
 * hashed-at-rest session credentials (see AuthSession) so they can be rotated and revoked.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTtlMinutes;

    public JwtService(@Value("${app.jwt-secret:}") String secret,
                      @Value("${app.access-token-ttl-minutes:15}") long accessTtlMinutes) {
        if (secret == null || secret.length() < 48) {
            throw new IllegalStateException(
                    "APP_JWT_SECRET must be set and at least 48 characters. Refusing to start with a weak/empty secret.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlMinutes = accessTtlMinutes;
    }

    public String createAccessToken(String userId, String email, String globalRole) {
        return createAccessToken(userId, email, globalRole, null);
    }

    /** @param sessionId the backing AuthSession id (claim {@code sid}) — powers "this device" in the sessions view. */
    public String createAccessToken(String userId, String email, String globalRole, String sessionId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .claim("role", globalRole)
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtlMinutes, ChronoUnit.MINUTES)));
        if (sessionId != null) {
            builder.claim("sid", sessionId);
        }
        return builder.signWith(key).compact();
    }

    /**
     * MFA challenge token (Phase 8): 5 minutes, typ=mfa — the auth filter only accepts
     * typ=access, so a challenge token can never be used as a session credential.
     */
    public String createMfaChallenge(String userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("typ", "mfa")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(5, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    /** Validates an MFA challenge token; returns the user id, or null when wrong type/expired/invalid. */
    public String parseMfaChallenge(String token) {
        try {
            Claims claims = parse(token).getPayload();
            return "mfa".equals(claims.get("typ", String.class)) ? claims.getSubject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public Jws<Claims> parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    }

    public long accessTtlSeconds() {
        return accessTtlMinutes * 60;
    }
}
