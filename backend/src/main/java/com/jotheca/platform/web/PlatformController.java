package com.jotheca.platform.web;

import com.jotheca.platform.PlatformService;
import com.jotheca.platform.dto.CreateLibraryRequest;
import com.jotheca.platform.dto.PlatformLibraryDto;
import com.jotheca.platform.dto.PlatformStatsDto;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** SUPER_ADMIN console API. Secured by role in SecurityConfig; never touches tenant operations. */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {

    private final PlatformService platformService;
    private final com.jotheca.audit.AuditQueryService auditQuery;

    public PlatformController(PlatformService platformService,
                              com.jotheca.audit.AuditQueryService auditQuery) {
        this.platformService = platformService;
        this.auditQuery = auditQuery;
    }

    /** Platform audit viewer (Phase 8): cross-tenant trail, optionally pinned to one tenant. */
    @GetMapping("/audit")
    public Page<com.jotheca.audit.AuditQueryService.AuditEntryDto> audit(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String actorEmail,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.Instant from,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.Instant to,
            Pageable pageable) {
        return auditQuery.searchPlatform(tenantId, action, actorEmail, from, to, pageable);
    }

    @GetMapping("/libraries")
    public Page<PlatformLibraryDto> libraries(@RequestParam(required = false) String status,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "30") int size) {
        return platformService.list(status,
                PageRequest.of(Math.min(page, 500), Math.min(size, 200), Sort.by("createdAt").descending()));
    }

    @PostMapping("/libraries")
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformLibraryDto create(@Valid @RequestBody CreateLibraryRequest req) {
        return platformService.create(req);
    }

    /** Assign a plan to a library's subscription (409 DOWNGRADE_BLOCKED when usage exceeds it). */
    @PostMapping("/libraries/{id}/plan")
    public com.jotheca.platform.dto.PlanChangeDto.PlanChangeResult changePlan(
            @PathVariable String id,
            @Valid @RequestBody com.jotheca.platform.dto.PlanChangeDto.ChangePlanRequest req) {
        return platformService.changePlan(id, req);
    }

    @PostMapping("/libraries/{id}/approve")
    public PlatformLibraryDto approve(@PathVariable String id) {
        return platformService.approve(id);
    }

    @PostMapping("/libraries/{id}/suspend")
    public PlatformLibraryDto suspend(@PathVariable String id) {
        return platformService.suspend(id);
    }

    @GetMapping("/stats")
    public PlatformStatsDto stats() {
        return platformService.stats();
    }
}
