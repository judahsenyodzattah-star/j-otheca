package com.libranet.audit.web;

import com.libranet.audit.AuditQueryService;
import com.libranet.audit.AuditQueryService.AuditEntryDto;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-plane audit viewer (Phase 8): LIBRARY_ADMIN reads THIS library's trail only. */
@RestController
@RequestMapping("/api/v1/tenant/audit")
public class TenantAuditController {

    private final AuditQueryService auditQuery;

    public TenantAuditController(AuditQueryService auditQuery) {
        this.auditQuery = auditQuery;
    }

    @GetMapping
    public Page<AuditEntryDto> search(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actorEmail,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            Pageable pageable) {
        return auditQuery.searchOwnTenant(action, actorEmail, from, to, pageable);
    }
}
