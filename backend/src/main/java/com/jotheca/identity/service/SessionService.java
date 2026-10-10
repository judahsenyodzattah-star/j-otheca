package com.jotheca.identity.service;

import com.jotheca.audit.AuditService;
import com.jotheca.audit.model.AuditAction;
import com.jotheca.common.error.ApiException;
import com.jotheca.common.web.SecurityUsers;
import com.jotheca.identity.dto.SecurityDtos.SessionDto;
import com.jotheca.identity.model.AuthSession;
import com.jotheca.identity.repo.AuthSessionRepository;
import com.jotheca.identity.security.JwtService;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sessions & devices (Phase 8, spec §36): every refresh-token session with its device label,
 * source IP and last-active time; the user can revoke one device or sign out everywhere.
 * Rotation/reuse-detection stays in AuthService; this is the management view.
 */
@Service
public class SessionService {

    private final AuthSessionRepository sessions;
    private final SessionRevoker revoker;
    private final JwtService jwt;
    private final AuditService audit;

    public SessionService(AuthSessionRepository sessions, SessionRevoker revoker,
                          JwtService jwt, AuditService audit) {
        this.sessions = sessions;
        this.revoker = revoker;
        this.jwt = jwt;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<SessionDto> listMine(String authorizationHeader) {
        String userId = SecurityUsers.current().id();
        String currentSid = sidOf(authorizationHeader);
        Instant now = Instant.now();
        return sessions.findByUserIdAndRevokedAtIsNull(userId).stream()
                .filter(s -> s.getExpiresAt().isAfter(now))
                .sorted(Comparator.comparing(AuthSession::getCreatedAt).reversed())
                .map(s -> new SessionDto(s.getId(), s.getDeviceInfo(), s.getIp(),
                        s.getCreatedAt(), s.getLastUsedAt(), s.getId().equals(currentSid)))
                .toList();
    }

    /** Revoke one of my sessions. Another user's session id answers 404 — existence is hidden. */
    @Transactional
    public void revokeMine(String sessionId) {
        String userId = SecurityUsers.current().id();
        AuthSession session = sessions.findById(sessionId)
                .filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("SESSION_NOT_FOUND", "Session not found"));
        if (session.getRevokedAt() == null) {
            session.setRevokedAt(Instant.now());
            sessions.save(session);
            audit.log(AuditAction.SESSION_REVOKED, userId, SecurityUsers.current().email(),
                    "AuthSession", sessionId, Map.of("device", session.getDeviceInfo() == null ? "" : session.getDeviceInfo()));
        }
    }

    /** Sign out everywhere (all devices incl. this one — client must re-login). */
    @Transactional
    public void revokeAllMine() {
        String userId = SecurityUsers.current().id();
        revoker.revokeAllForUser(userId);
        audit.log(AuditAction.SESSIONS_REVOKED, userId, SecurityUsers.current().email(),
                "User", userId, Map.of());
    }

    private String sidOf(String authorizationHeader) {
        try {
            if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
                return jwt.parse(authorizationHeader.substring(7)).getPayload().get("sid", String.class);
            }
        } catch (Exception ignored) {
            // treated as "no current marker"
        }
        return null;
    }
}
