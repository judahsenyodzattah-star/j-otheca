package com.libranet.permissions.web;

import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.membership.model.LibraryRole;
import com.libranet.permissions.model.LibraryPerm;
import com.libranet.permissions.service.PermissionService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-staff permission management (audit P4/E19). Admin tunes librarians; anyone staff-side reads /me. */
@RestController
@RequestMapping("/api/v1/tenant/permissions")
public class TenantPermissionsController {

    private final PermissionService permissions;

    public TenantPermissionsController(PermissionService permissions) {
        this.permissions = permissions;
    }

    /** The toggle catalog the UI renders — label + whether librarians hold it by default. */
    @Operation(summary = "Permission catalog")
    @GetMapping("/catalog")
    public List<Map<String, Object>> catalog() {
        TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        return Arrays.stream(LibraryPerm.values())
                .map(p -> Map.<String, Object>of(
                        "key", p.name(), "label", p.label(), "librarianDefault", p.isLibrarianDefault()))
                .toList();
    }

    @Operation(summary = "My effective permissions")
    @GetMapping("/me")
    public Map<String, Object> me() {
        TenantContext.Current ctx = TenantGuard.requireRole(
                LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        if (ctx.hasAnyRole(LibraryRole.LIBRARY_ADMIN.name())) {
            return Map.of("role", "LIBRARY_ADMIN",
                    "effective", Arrays.stream(LibraryPerm.values()).map(Enum::name).toList());
        }
        Set<LibraryPerm> eff = permissions.effective(ctx.libraryId(), ctx.membershipId());
        return Map.of("role", "LIBRARIAN", "effective", eff.stream().map(Enum::name).toList());
    }

    public record OverrideEntry(String perm, Boolean granted) {} // granted null = back to default
    public record SetOverridesRequest(List<OverrideEntry> overrides) {}

    @Operation(summary = "A staff member's effective permissions + overrides (admin)")
    @GetMapping("/staff/{membershipId}")
    public Map<String, Object> staffView(@PathVariable String membershipId) {
        TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name());
        TenantContext.Current ctx = TenantContext.require();
        Set<LibraryPerm> eff = permissions.effective(ctx.libraryId(), membershipId);
        return Map.of(
                "effective", eff.stream().map(Enum::name).toList(),
                "overrides", permissions.overridesView(membershipId));
    }

    @Operation(summary = "Grant / revoke / reset permissions for a librarian (admin)")
    @PutMapping("/staff/{membershipId}")
    public Map<String, Object> set(@PathVariable String membershipId,
                                   @RequestBody SetOverridesRequest req) {
        TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name());
        if (req.overrides() == null || req.overrides().isEmpty()) {
            throw ApiException.badRequest("OVERRIDES_REQUIRED", "Send at least one override.");
        }
        if (req.overrides().size() > LibraryPerm.values().length) {
            throw ApiException.badRequest("OVERRIDES_TOO_MANY", "One entry per permission at most.");
        }
        for (OverrideEntry o : req.overrides()) {
            LibraryPerm perm;
            try {
                perm = LibraryPerm.valueOf(o.perm().trim().toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                throw ApiException.badRequest("PERM_INVALID", "Unknown permission: " + o.perm());
            }
            permissions.setOverride(membershipId, perm, o.granted());
        }
        return staffView(membershipId);
    }
}
