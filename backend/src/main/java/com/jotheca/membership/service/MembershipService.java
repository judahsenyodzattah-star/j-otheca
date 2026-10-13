package com.jotheca.membership.service;

import com.jotheca.audit.AuditService;
import com.jotheca.billing.service.EntitlementService;
import com.jotheca.audit.model.AuditAction;
import com.jotheca.common.error.ApiException;
import com.jotheca.common.tenant.CodeGenerator;
import com.jotheca.common.tenant.TenantContext;
import com.jotheca.common.tenant.TenantGuard;
import com.jotheca.common.web.AuthUser;
import com.jotheca.common.web.SecurityUsers;
import com.jotheca.identity.model.User;
import com.jotheca.identity.repo.UserRepository;
import com.jotheca.membership.dto.JoinRequest;
import com.jotheca.membership.dto.MemberDto;
import com.jotheca.membership.dto.MyMembershipDto;
import com.jotheca.membership.model.Invitation;
import com.jotheca.membership.model.LibraryRole;
import com.jotheca.membership.model.Membership;
import com.jotheca.membership.model.MembershipStatus;
import com.jotheca.membership.model.MembershipType;
import com.jotheca.membership.repo.InvitationRepository;
import com.jotheca.membership.repo.MembershipRepository;
import com.jotheca.tenancy.model.JoinPolicy;
import com.jotheca.tenancy.model.Library;
import com.jotheca.tenancy.model.LibraryStatus;
import com.jotheca.tenancy.repo.LibraryRepository;
import com.jotheca.tenancy.service.LibraryService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.jotheca.permissions.model.LibraryPerm;
import com.jotheca.permissions.service.PermissionService;

@Service
public class MembershipService {

    private final MembershipRepository memberships;
    private final InvitationRepository invitations;
    private final LibraryRepository libraries;
    private final UserRepository users;
    private final AuditService audit;
    private final EntitlementService entitlements;

        private final PermissionService permissions;

    public MembershipService(MembershipRepository memberships, InvitationRepository invitations,
                             LibraryRepository libraries, UserRepository users, AuditService audit,
                             EntitlementService entitlements,
                            PermissionService permissions) {
        this.permissions = permissions;
        this.memberships = memberships;
        this.invitations = invitations;
        this.libraries = libraries;
        this.users = users;
        this.audit = audit;
        this.entitlements = entitlements;
    }

    // ---------- joining (authenticated patron) ----------

    @Transactional
    public MemberDto join(String librarySlug, JoinRequest req) {
        AuthUser me = SecurityUsers.current();
        User user = users.findById(me.id())
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Authentication required"));
        Library library = libraries.findBySlug(librarySlug)
                .filter(l -> l.getStatus() == LibraryStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("LIBRARY_NOT_FOUND", "Library not found"));

        Optional<Membership> existing = memberships.findByTenantIdAndUserId(library.getId(), user.getId());
        if (existing.isPresent()) {
            MembershipStatus s = existing.get().getStatus();
            if (s == MembershipStatus.APPROVED) {
                throw ApiException.conflict("ALREADY_MEMBER", "You are already a member of this library");
            }
            if (s == MembershipStatus.PENDING) {
                throw ApiException.conflict("ALREADY_REQUESTED", "Your membership request is already pending approval");
            }
            // REJECTED / SUSPENDED / EXPIRED → re-application reuses the row (keeps history & unique constraint)
            return reapply(existing.get(), library, user, req);
        }

        Membership membership = new Membership();
        membership.setTenantId(library.getId());
        membership.setUserId(user.getId());
        applyOutcome(membership, decideOutcome(library, user, req));
        memberships.save(membership);
        auditAfterJoin(membership, library);
        return MemberDto.from(membership, user);
    }

    private MemberDto reapply(Membership membership, Library library, User user, JoinRequest req) {
        applyOutcome(membership, decideOutcome(library, user, req));
        memberships.save(membership);
        auditAfterJoin(membership, library);
        return MemberDto.from(membership, user);
    }

    private void auditAfterJoin(Membership membership, Library library) {
        boolean approved = membership.getStatus() == MembershipStatus.APPROVED;
        audit.log(approved ? AuditAction.MEMBERSHIP_JOINED : AuditAction.MEMBERSHIP_REQUESTED,
                null, null, "Membership", membership.getId(),
                Map.of("libraryUid", library.getUid(), "status", membership.getStatus().name()));
        // NOTE: audit tenant_id is NULL here because TenantContext is not armed on the public join endpoint;
        // the library reference is captured in metadata. Tenant-armed audit applies to /tenant/** actions.
    }

    // ---------- policy engine (spec §13) ----------

    private record Outcome(MembershipStatus status, MembershipType type, Invitation invitation) {}

    private Outcome decideOutcome(Library library, User user, JoinRequest req) {
        JoinPolicy policy = library.getJoinPolicy();
        Map<String, String> payload = req.verificationPayload() == null ? Map.of() : req.verificationPayload();
        switch (policy) {
            case PUBLIC:
                return new Outcome(MembershipStatus.APPROVED, requestedType(req, MembershipType.PUBLIC_MEMBER), null);
            case APPROVAL_REQUIRED:
            case CUSTOM:
                return new Outcome(MembershipStatus.PENDING, requestedType(req, MembershipType.PUBLIC_MEMBER), null);
            case STUDENT_ONLY: {
                requirePayload(payload, List.of("studentId"), "STUDENT_VERIFICATION_REQUIRED",
                        "This library requires student verification (studentId)");
                return new Outcome(MembershipStatus.PENDING, MembershipType.STUDENT, null);
            }
            case STAFF_ONLY: {
                requirePayload(payload, List.of("employeeId"), "STAFF_VERIFICATION_REQUIRED",
                        "This library requires staff verification (employeeId)");
                return new Outcome(MembershipStatus.PENDING, MembershipType.STAFF, null);
            }
            case DOMAIN_VERIFIED: {
                String domain = library.getAllowedDomain();
                String email = user.getEmail() == null ? "" : user.getEmail().toLowerCase(Locale.ROOT);
                if (domain == null || domain.isBlank() || !email.endsWith("@" + domain.toLowerCase(Locale.ROOT))) {
                    throw ApiException.forbidden("DOMAIN_NOT_ALLOWED",
                            "This library requires an institutional email at " + (domain == null ? "the institution domain" : "@" + domain));
                }
                return new Outcome(MembershipStatus.APPROVED, requestedType(req, MembershipType.STUDENT), null);
            }
            case INVITATION_ONLY: {
                Invitation invitation = resolveUsableInvitation(req.inviteCode(), library.getId());
                invitation.setUsedCount(invitation.getUsedCount() + 1);
                invitations.save(invitation);
                return new Outcome(invitation.isAutoApprove() ? MembershipStatus.APPROVED : MembershipStatus.PENDING,
                        invitation.getMembershipType(), invitation);
            }
            default:
                throw ApiException.badRequest("UNSUPPORTED_POLICY", "Unsupported join policy");
        }
    }

    private Outcome applyOutcome(Membership membership, Outcome outcome) {
        membership.setRole(LibraryRole.PATRON);
        membership.setMembershipType(outcome.type());
        membership.setStatus(outcome.status());
        if (outcome.status() == MembershipStatus.APPROVED) {
            membership.setMembershipNumber(CodeGenerator.membershipNumber());
            membership.setJoinedAt(Instant.now());
        }
        return outcome;
    }

    private MembershipType requestedType(JoinRequest req, MembershipType fallback) {
        if (req.membershipType() == null || req.membershipType().isBlank()) {
            return fallback;
        }
        return LibraryService.parseEnum(MembershipType.class, req.membershipType(), "INVALID_MEMBERSHIP_TYPE");
    }

    private void requirePayload(Map<String, String> payload, List<String> keys, String code, String message) {
        boolean ok = keys.stream().anyMatch(k -> payload.containsKey(k) && !payload.get(k).isBlank());
        if (!ok) {
            throw ApiException.badRequest(code, message);
        }
    }

    private Invitation resolveUsableInvitation(String codeOrSlug, String libraryId) {
        if (codeOrSlug == null || codeOrSlug.isBlank()) {
            throw ApiException.forbidden("INVITATION_REQUIRED", "This library can only be joined with an invitation");
        }
        String key = codeOrSlug.trim();
        Invitation invitation = invitations.findByCode(key)
                .or(() -> invitations.findBySlug(key))
                .orElseThrow(() -> ApiException.notFound("INVITATION_NOT_FOUND", "Invitation not found"));
        if (!Objects.equals(invitation.getTenantId(), libraryId)) {
            // IDOR guard: an invitation is only valid inside its own library
            throw ApiException.forbidden("INVITATION_WRONG_LIBRARY", "This invitation belongs to a different library");
        }
        if (!invitation.usable(Instant.now())) {
            throw ApiException.badRequest("INVITATION_NOT_USABLE", "This invitation is no longer active");
        }
        return invitation;
    }

    // ---------- staff endpoints (tenant plane) ----------

    @Transactional(readOnly = true)
    public Page<MemberDto> listMembers(String status, Pageable pageable) {
        TenantContext.Current ctx = TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name(), LibraryRole.LIBRARIAN.name());
        Page<Membership> page = (status == null || status.isBlank())
                ? memberships.findByTenantId(ctx.libraryId(), pageable)
                : memberships.findByTenantIdAndStatus(ctx.libraryId(),
                        LibraryService.parseEnum(MembershipStatus.class, status, "INVALID_STATUS"), pageable);
        Map<String, User> byId = users.findAllById(page.getContent().stream().map(Membership::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return page.map(m -> MemberDto.from(m, byId.get(m.getUserId())));
    }

    @Transactional
    public MemberDto approve(String membershipId) {
        permissions.require(LibraryPerm.MEMBERS_MANAGE);
        Membership membership = loadInTenant(membershipId);
        if (membership.getStatus() != MembershipStatus.PENDING) {
            throw ApiException.conflict("NOT_PENDING", "Only pending requests can be approved");
        }
        entitlements.requireMemberCapacity(membership.getTenantId());
        membership.setStatus(MembershipStatus.APPROVED);
        membership.setMembershipNumber(CodeGenerator.membershipNumber());
        membership.setJoinedAt(Instant.now());
        libraries.findById(membership.getTenantId()).ifPresent(l -> l.setMemberCount(l.getMemberCount() + 1));
        audit.log(AuditAction.MEMBERSHIP_APPROVED, "Membership", membership.getId(), Map.of());
        return MemberDto.from(membership, users.findById(membership.getUserId()).orElse(null));
    }

    @Transactional
    public MemberDto reject(String membershipId) {
        permissions.require(LibraryPerm.MEMBERS_MANAGE);
        Membership membership = loadInTenant(membershipId);
        if (membership.getStatus() != MembershipStatus.PENDING) {
            throw ApiException.conflict("NOT_PENDING", "Only pending requests can be rejected");
        }
        membership.setStatus(MembershipStatus.REJECTED);
        audit.log(AuditAction.MEMBERSHIP_REJECTED, "Membership", membership.getId(), Map.of());
        return MemberDto.from(membership, users.findById(membership.getUserId()).orElse(null));
    }

    @Transactional
    public MemberDto suspend(String membershipId) {
        permissions.require(LibraryPerm.MEMBERS_MANAGE);
        Membership membership = loadInTenant(membershipId);
        if (membership.getStatus() != MembershipStatus.APPROVED) {
            throw ApiException.conflict("NOT_APPROVED", "Only approved memberships can be suspended");
        }
        membership.setStatus(MembershipStatus.SUSPENDED);
        libraries.findById(membership.getTenantId()).ifPresent(l -> l.setMemberCount(Math.max(0, l.getMemberCount() - 1)));
        audit.log(AuditAction.MEMBERSHIP_SUSPENDED, "Membership", membership.getId(), Map.of());
        return MemberDto.from(membership, users.findById(membership.getUserId()).orElse(null));
    }

    /**
     * Appoint/demote staff (spec §9: librarians are appointed by the Library Admin).
     * Staff headcount is plan-limited via EntitlementService. LIBRARY_ADMIN transfer is out of scope.
     */
    @Transactional
    public MemberDto changeRole(String membershipId, String roleRaw) {
        TenantContext.Current ctx = TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name());
        LibraryRole target;
        try {
            target = LibraryRole.valueOf(roleRaw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_ROLE", "Role must be LIBRARIAN or PATRON");
        }
        if (target == LibraryRole.LIBRARY_ADMIN) {
            throw ApiException.badRequest("ADMIN_TRANSFER_UNSUPPORTED", "Ownership transfer is a separate flow");
        }
        Membership membership = loadInTenant(membershipId);
        if (membership.getUserId().equals(SecurityUsers.current().id())) {
            throw ApiException.badRequest("CANNOT_CHANGE_OWN_ROLE", "You cannot change your own role");
        }
        if (membership.getStatus() != MembershipStatus.APPROVED) {
            throw ApiException.conflict("NOT_APPROVED", "Only approved members can change roles");
        }
        if (membership.getRole() == LibraryRole.LIBRARY_ADMIN) {
            throw ApiException.forbidden("CANNOT_DEMOTE_ADMIN", "A library admin cannot be demoted here");
        }
        if (membership.getRole() == target) {
            return MemberDto.from(membership, users.findById(membership.getUserId()).orElse(null));
        }
        if (target == LibraryRole.LIBRARIAN) {
            entitlements.requireStaffCapacity(ctx.libraryId());
        }
        String from = membership.getRole().name();
        membership.setRole(target);
        audit.log(AuditAction.ROLE_CHANGED, "Membership", membership.getId(),
                Map.of("from", from, "to", target.name()));
        return MemberDto.from(membership, users.findById(membership.getUserId()).orElse(null));
    }

    /** IDOR-safe by-id load within the current tenant. */
    private Membership loadInTenant(String membershipId) {
        Membership membership = memberships.findById(membershipId)
                .orElseThrow(() -> ApiException.notFound("MEMBERSHIP_NOT_FOUND", "Membership not found"));
        TenantGuard.requireBelongsToCurrentTenant(membership.getTenantId());
        return membership;
    }

    // ---------- self-service ----------

    @Transactional(readOnly = true)
    public List<MyMembershipDto> listMine() {
        AuthUser me = SecurityUsers.current();
        List<Membership> mine = memberships.findByUserId(me.id());
        Map<String, Library> byId = libraries.findAllById(mine.stream().map(Membership::getTenantId).toList())
                .stream().collect(Collectors.toMap(Library::getId, Function.identity()));
        return mine.stream().map(m -> MyMembershipDto.from(m, byId.get(m.getTenantId()))).toList();
    }

    @Transactional(readOnly = true)
    public MyMembershipDto myCard() {
        TenantContext.Current ctx = TenantContext.require();
        Membership membership = memberships.findById(ctx.membershipId())
                .orElseThrow(() -> ApiException.notFound("MEMBERSHIP_NOT_FOUND", "Membership not found"));
        Library library = libraries.findById(ctx.libraryId()).orElse(null);
        return MyMembershipDto.from(membership, library);
    }
}
