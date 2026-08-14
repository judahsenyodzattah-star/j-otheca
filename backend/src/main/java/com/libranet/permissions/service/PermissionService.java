package com.libranet.permissions.service;

import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.membership.model.LibraryRole;
import com.libranet.membership.model.Membership;
import com.libranet.membership.model.MembershipStatus;
import com.libranet.membership.repo.MembershipRepository;
import com.libranet.permissions.model.LibraryPerm;
import com.libranet.permissions.model.StaffPermission;
import com.libranet.permissions.repo.StaffPermissionRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Effective staff permissions (audit P4/E19). Resolution order:
 *   LIBRARY_ADMIN → every permission, immutable (a tenant can never lock its own admin out);
 *   LIBRARIAN     → the default pack, adjusted by this member's override rows;
 *   PATRON        → nothing.
 * {@code require} returns one legacy 403, code {@code INSUFFICIENT_LIBRARY_ROLE}, for everyone
 * without access — role mismatch and missing permission are indistinguishable by design, so
 * denial never leaks which capabilities a tenant's staff roster holds (spec §27/§40: stable
 * contract + error invisibility). Clients learn capabilities from {@code GET /permissions/me}.
 */
@Service
public class PermissionService {

    private final StaffPermissionRepository overrides;
    private final MembershipRepository memberships;

    public PermissionService(StaffPermissionRepository overrides, MembershipRepository memberships) {
        this.overrides = overrides;
        this.memberships = memberships;
    }

    /** Effective permission set for one membership inside its tenant. */
    @Transactional(readOnly = true)
    public Set<LibraryPerm> effective(String tenantId, String membershipId) {
        Membership m = memberships.findById(membershipId)
                .orElseThrow(() -> ApiException.notFound("MEMBERSHIP_NOT_FOUND", "Membership not found"));
        TenantGuard.requireBelongsToCurrentTenant(m.getTenantId());
        if (m.getRole() == LibraryRole.LIBRARY_ADMIN) {
            return EnumSet.allOf(LibraryPerm.class);
        }
        if (m.getRole() != LibraryRole.LIBRARIAN || m.getStatus() != MembershipStatus.APPROVED) {
            return Set.of();
        }
        EnumSet<LibraryPerm> eff = EnumSet.copyOf(LibraryPerm.librarianDefaults());
        for (StaffPermission o : overrides.findByTenantIdAndMembershipId(tenantId, membershipId)) {
            LibraryPerm p = LibraryPerm.valueOf(o.getPerm());
            if (o.isGranted()) eff.add(p); else eff.remove(p);
        }
        return eff;
    }

    /** The guard every tenant mutator routes through now. Same shape as TenantGuard.requireRole. */
    @Transactional(readOnly = true)
    public TenantContext.Current require(LibraryPerm perm) {
        TenantContext.Current ctx = TenantGuard.requireRole(
                LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        if (ctx.hasAnyRole(LibraryRole.LIBRARY_ADMIN.name())) {
            return ctx;
        }
        if (!effective(ctx.libraryId(), ctx.membershipId()).contains(perm)) {
            throw ApiException.forbidden("INSUFFICIENT_LIBRARY_ROLE",
                    "This library role cannot perform that action."); // same tenet as TenantGuard
        }
        return ctx;
    }

    /** Admin-only override edit. Target must be an approved librarian of this tenant. */
    @Transactional
    public void setOverride(String membershipId, LibraryPerm perm, Boolean granted) {
        TenantContext.Current ctx = TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name());
        Membership target = memberships.findById(membershipId)
                .orElseThrow(() -> ApiException.notFound("MEMBERSHIP_NOT_FOUND", "Membership not found"));
        TenantGuard.requireBelongsToCurrentTenant(target.getTenantId());
        if (target.getRole() == LibraryRole.LIBRARY_ADMIN) {
            throw ApiException.badRequest("ADMIN_PERMS_IMMUTABLE",
                    "Library admins always hold every permission — overrides apply to librarians.");
        }
        if (target.getRole() != LibraryRole.LIBRARIAN || target.getStatus() != MembershipStatus.APPROVED) {
            throw ApiException.badRequest("TARGET_NOT_STAFF",
                    "Permissions can only be tuned for approved librarian members.");
        }
        var row = overrides.findByTenantIdAndMembershipIdAndPerm(
                ctx.libraryId(), membershipId, perm.name());
        if (granted == null) {                      // back to the role default
            row.ifPresent(overrides::delete);
            return;
        }
        StaffPermission sp = row.orElseGet(() -> {
            StaffPermission n = new StaffPermission();
            n.setTenantId(ctx.libraryId());
            n.setMembershipId(membershipId);
            n.setPerm(perm.name());
            return n;
        });
        sp.setGranted(granted);
        overrides.save(sp);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> overridesView(String membershipId) {
        TenantContext.Current ctx = TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name());
        Membership target = memberships.findById(membershipId)
                .orElseThrow(() -> ApiException.notFound("MEMBERSHIP_NOT_FOUND", "Membership not found"));
        TenantGuard.requireBelongsToCurrentTenant(target.getTenantId());
        return overrides.findByTenantIdAndMembershipId(ctx.libraryId(), membershipId).stream()
                .map(o -> Map.<String, Object>of("perm", o.getPerm(), "granted", o.isGranted()))
                .toList();
    }
}
