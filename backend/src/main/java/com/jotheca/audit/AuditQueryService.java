package com.jotheca.audit;

import com.jotheca.audit.model.AuditLog;
import com.jotheca.audit.repo.AuditLogRepository;
import com.jotheca.common.tenant.TenantContext;
import com.jotheca.common.tenant.TenantGuard;
import com.jotheca.membership.model.LibraryRole;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The audit VIEWER (Phase 8, spec §37). Two planes with hard scoping:
 *  - tenant: LIBRARY_ADMIN only, ALWAYS pinned to the resolved tenant (never a client param);
 *  - platform: SUPER_ADMIN (see platform controller), may filter by any tenant or none.
 * Read-only — audit rows are never mutated, only appended by AuditService.
 */
@Service
public class AuditQueryService {

    public record AuditEntryDto(String id, String tenantId, String actorId, String actorEmail,
                                String action, String entityType, String entityId, String ip,
                                String metadata, Instant createdAt) {
        static AuditEntryDto of(AuditLog e) {
            return new AuditEntryDto(e.getId(), e.getTenantId(), e.getActorId(), e.getActorEmail(),
                    e.getAction(), e.getEntityType(), e.getEntityId(), e.getIp(),
                    e.getMetadata(), e.getCreatedAt());
        }
    }

    private final AuditLogRepository auditLogs;

    public AuditQueryService(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    /** Tenant plane: own tenant only, regardless of any client-supplied filter. */
    @Transactional(readOnly = true)
    public Page<AuditEntryDto> searchOwnTenant(String action, String actorEmail,
                                               Instant from, Instant to, Pageable pageable) {
        TenantContext.Current ctx = TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name());
        return search(ctx.libraryId(), action, actorEmail, from, to, pageable);
    }

    /** Platform plane (SUPER_ADMIN checked by security config). tenantId null = all tenants. */
    @Transactional(readOnly = true)
    public Page<AuditEntryDto> searchPlatform(String tenantId, String action, String actorEmail,
                                              Instant from, Instant to, Pageable pageable) {
        return search(tenantId, action, actorEmail, from, to, pageable);
    }

    private Page<AuditEntryDto> search(String tenantId, String action, String actorEmail,
                                       Instant from, Instant to, Pageable pageable) {
        Specification<AuditLog> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (tenantId != null && !tenantId.isBlank()) {
                ps.add(cb.equal(root.get("tenantId"), tenantId));
            }
            if (action != null && !action.isBlank()) {
                ps.add(cb.equal(root.get("action"), action));
            }
            if (actorEmail != null && !actorEmail.isBlank()) {
                ps.add(cb.like(cb.lower(root.get("actorEmail")),
                        "%" + actorEmail.toLowerCase(java.util.Locale.ROOT) + "%"));
            }
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Pageable sorted = pageable.getSort().isSorted() ? pageable
                : PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 200),
                        Sort.by(Sort.Direction.DESC, "createdAt"));
        return auditLogs.findAll(spec, sorted).map(AuditEntryDto::of);
    }
}
