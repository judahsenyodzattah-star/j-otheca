package com.libranet.membership.service;

import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.common.error.ApiException;
import com.libranet.common.tenant.CodeGenerator;
import com.libranet.common.tenant.TenantContext;
import com.libranet.common.tenant.TenantGuard;
import com.libranet.membership.dto.CreateInvitationRequest;
import com.libranet.membership.dto.InvitationDto;
import com.libranet.membership.dto.InvitationPreviewDto;
import com.libranet.membership.model.Invitation;
import com.libranet.membership.model.LibraryRole;
import com.libranet.membership.model.MembershipType;
import com.libranet.membership.repo.InvitationRepository;
import com.libranet.tenancy.model.Library;
import com.libranet.tenancy.model.LibraryStatus;
import com.libranet.tenancy.repo.LibraryRepository;
import com.libranet.tenancy.service.LibraryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationService {

    private final InvitationRepository invitations;
    private final LibraryRepository libraries;
    private final AuditService audit;
    private final String publicBaseUrl;

    public InvitationService(InvitationRepository invitations, LibraryRepository libraries,
                             AuditService audit, @Value("${app.public-base-url}") String publicBaseUrl) {
        this.invitations = invitations;
        this.libraries = libraries;
        this.audit = audit;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Transactional
    public InvitationDto create(CreateInvitationRequest req) {
        TenantContext.Current ctx = TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name());
        Library library = libraries.findById(ctx.libraryId())
                .orElseThrow(() -> ApiException.notFound("LIBRARY_NOT_FOUND", "Library not found"));

        Invitation invitation = new Invitation();
        invitation.setTenantId(ctx.libraryId());
        invitation.setSlug(uniqueSlug(req.slug(), library.getSlug()));
        String code;
        do { code = CodeGenerator.invitationCode(); } while (invitations.existsByCode(code));
        invitation.setCode(code);
        invitation.setAllowedRole(LibraryRole.PATRON); // librarians are appointed, never invited (spec §9)
        invitation.setMembershipType(req.membershipType() == null
                ? MembershipType.PUBLIC_MEMBER
                : LibraryService.parseEnum(MembershipType.class, req.membershipType(), "INVALID_MEMBERSHIP_TYPE"));
        invitation.setAutoApprove(req.autoApprove() == null || req.autoApprove());
        invitation.setMaxUses(req.maxUses());
        invitation.setExpiresAt(req.expiresAt());
        invitations.save(invitation);
        audit.log(AuditAction.INVITATION_CREATED, "Invitation", invitation.getId(), Map.of("slug", invitation.getSlug()));
        return InvitationDto.from(invitation, publicBaseUrl);
    }

    @Transactional(readOnly = true)
    public List<InvitationDto> list() {
        TenantContext.Current ctx = TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name());
        return invitations.findByTenantId(ctx.libraryId()).stream()
                .map(i -> InvitationDto.from(i, publicBaseUrl))
                .toList();
    }

    @Transactional
    public void deactivate(String invitationId) {
        TenantGuard.requireRole(LibraryRole.LIBRARY_ADMIN.name());
        Invitation invitation = invitations.findById(invitationId)
                .orElseThrow(() -> ApiException.notFound("INVITATION_NOT_FOUND", "Invitation not found"));
        TenantGuard.requireBelongsToCurrentTenant(invitation.getTenantId());
        invitation.setActive(false);
        audit.log(AuditAction.INVITATION_DEACTIVATED, "Invitation", invitation.getId(), Map.of("slug", invitation.getSlug()));
    }

    /** Public preview — no auth. Works with slug (link/QR) or short code. */
    @Transactional(readOnly = true)
    public InvitationPreviewDto preview(String key) {
        Invitation invitation = invitations.findBySlug(key).or(() -> invitations.findByCode(key))
                .filter(i -> i.usable(Instant.now()))
                .orElseThrow(() -> ApiException.notFound("INVITATION_NOT_FOUND",
                        "This invitation link is invalid or has expired"));
        Library library = libraries.findById(invitation.getTenantId())
                .filter(l -> l.getStatus() == LibraryStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("LIBRARY_NOT_FOUND", "Library not found"));
        return InvitationPreviewDto.from(invitation, library);
    }

    private String uniqueSlug(String requested, String librarySlug) {
        String base = (requested != null && !requested.isBlank())
                ? CodeGenerator.slugify(requested)
                : librarySlug + "-" + CodeGenerator.randomToken(4).toLowerCase();
        String candidate = base;
        int suffix = 2;
        while (invitations.existsBySlug(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }
}
