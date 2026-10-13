package com.jotheca.audit;

import com.jotheca.audit.model.AuditAction;
import com.jotheca.audit.model.AuditLog;
import com.jotheca.audit.repo.AuditLogRepository;
import com.jotheca.common.tenant.TenantContext;
import com.jotheca.common.web.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tenant-aware audit writer (spec §37). NEVER log passwords, tokens, secrets here.
 * Uses REQUIRES_NEW so audit rows survive a business transaction that later rolls back
 * (e.g. failed login attempts are still recorded).
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(AuditAction action, String entityType, String entityId, Map<String, ?> metadata) {
        String actorId = null;
        String actorEmail = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser user) {
            actorId = user.id();
            actorEmail = user.email();
        }
        log(action, actorId, actorEmail, entityType, entityId, metadata);
    }

    /** Explicit actor variant for flows where the user is not (yet) authenticated, e.g. failed login. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(AuditAction action, String actorId, String actorEmail,
                    String entityType, String entityId, Map<String, ?> metadata) {
        AuditLog entry = new AuditLog();
        TenantContext.Current ctx = TenantContext.get();
        entry.setTenantId(ctx != null ? ctx.libraryId() : null);
        entry.setActorId(actorId);
        entry.setActorEmail(actorEmail);
        entry.setAction(action.name());
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setIp(currentIp());
        try {
            entry.setMetadata(metadata == null || metadata.isEmpty() ? null : mapper.writeValueAsString(metadata));
        } catch (Exception e) {
            entry.setMetadata("{\"serialization\":\"failed\"}");
        }
        repo.save(entry);
        log.debug("audit {} {} {}", action, entityType, entityId);
    }

    private String currentIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String forwarded = req.getHeader("X-Forwarded-For");
            return forwarded != null ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}
