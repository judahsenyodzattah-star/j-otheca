package com.jotheca.platform;

import com.jotheca.audit.AuditService;
import com.jotheca.audit.model.AuditAction;
import com.jotheca.billing.model.LibrarySubscription;
import com.jotheca.billing.model.SubscriptionPlan;
import com.jotheca.billing.repo.LibrarySubscriptionRepository;
import com.jotheca.billing.repo.SubscriptionPlanRepository;
import com.jotheca.billing.service.EntitlementService;
import com.jotheca.billing.service.PlanLimits;
import com.jotheca.billing.service.StorageUsageService;
import com.jotheca.catalog.repo.BookRepository;
import com.jotheca.catalog.repo.BranchRepository;
import com.jotheca.common.error.ApiException;
import com.jotheca.identity.repo.UserRepository;
import com.jotheca.membership.model.MembershipStatus;
import com.jotheca.membership.repo.MembershipRepository;
import com.jotheca.platform.dto.CreateLibraryRequest;
import com.jotheca.platform.dto.PlanChangeDto;
import com.jotheca.platform.dto.PlatformLibraryDto;
import com.jotheca.platform.dto.PlatformStatsDto;
import com.jotheca.tenancy.model.JoinPolicy;
import com.jotheca.tenancy.model.Library;
import com.jotheca.tenancy.model.LibraryStatus;
import com.jotheca.tenancy.model.LibraryType;
import com.jotheca.tenancy.repo.LibraryRepository;
import com.jotheca.tenancy.service.LibraryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform plane (SUPER_ADMIN). Deliberately offers ONLY tenancy lifecycle + visibility —
 * no circulation/member-approval operations here; those belong to libraries (spec §8).
 */
@Service
public class PlatformService {

    private final LibraryRepository libraries;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final LibraryService libraryService;
    private final LibrarySubscriptionRepository subscriptions;
    private final SubscriptionPlanRepository plans;
    private final BookRepository books;
    private final BranchRepository branches;
    private final StorageUsageService storageUsage;
    private final EntitlementService entitlements;
    private final AuditService audit;

    public PlatformService(LibraryRepository libraries, UserRepository users,
                           MembershipRepository memberships, LibraryService libraryService,
                           LibrarySubscriptionRepository subscriptions, SubscriptionPlanRepository plans,
                           BookRepository books, BranchRepository branches,
                           StorageUsageService storageUsage, EntitlementService entitlements,
                           AuditService audit) {
        this.libraries = libraries;
        this.users = users;
        this.memberships = memberships;
        this.libraryService = libraryService;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.books = books;
        this.branches = branches;
        this.storageUsage = storageUsage;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<PlatformLibraryDto> list(String status, Pageable pageable) {
        Specification<Library> spec = (root, q, cb) -> cb.conjunction();
        if (status != null && !status.isBlank()) {
            LibraryStatus parsed = LibraryService.parseEnum(LibraryStatus.class, status, "INVALID_STATUS");
            spec = (root, q, cb) -> cb.equal(root.get("status"), parsed);
        }
        return libraries.findAll(spec, pageable).map(PlatformLibraryDto::from);
    }

    @Transactional
    public PlatformLibraryDto create(CreateLibraryRequest req) {
        LibraryType type = LibraryService.parseEnum(LibraryType.class, req.type(), "INVALID_LIBRARY_TYPE");
        JoinPolicy policy = req.joinPolicy() == null ? null
                : LibraryService.parseEnum(JoinPolicy.class, req.joinPolicy(), "INVALID_JOIN_POLICY");
        return PlatformLibraryDto.from(libraryService.createByPlatform(
                req.name(), type, req.country(), req.city(), req.address(),
                req.description(), policy, req.visibility()));
    }

    /**
     * Plan assignment (Phase 9, spec §16/§31). Only data is mutated — the row's planId. Downgrades
     * (or sideways moves) that would orphan existing usage are REFUSED with 409 DOWNGRADE_BLOCKED:
     * the operator must shed usage (or the tenant buy add-ons) first. Never destroys data.
     */
    @Transactional
    public PlanChangeDto.PlanChangeResult changePlan(String libraryId, PlanChangeDto.ChangePlanRequest req) {
        Library library = libraries.findById(libraryId)
                .orElseThrow(() -> ApiException.notFound("LIBRARY_NOT_FOUND", "Library not found"));
        String code = req.planCode().trim().toUpperCase(java.util.Locale.ROOT);
        SubscriptionPlan target = plans.findByCode(code)
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "Unknown plan: " + code));
        LibrarySubscription sub = subscriptions.findByTenantId(libraryId)
                .orElseThrow(() -> ApiException.notFound("SUBSCRIPTION_NOT_FOUND",
                        "Library has no subscription row"));
        SubscriptionPlan current = plans.findById(sub.getPlanId()).orElse(null);
        if (current != null && current.getId().equals(target.getId())) {
            throw ApiException.badRequest("PLAN_UNCHANGED", "Library is already on plan " + code);
        }

        PlanLimits targetLimits = entitlements.parse(target);
        java.util.Map<String, Object> breached = new java.util.LinkedHashMap<>();
        long members = memberships.countByTenantIdAndStatus(libraryId, MembershipStatus.APPROVED);
        if (members > targetLimits.maxMembers()) breached.put("members",
                java.util.Map.of("current", members, "limit", targetLimits.maxMembers()));
        long staff = memberships.count((root, q, cb) -> cb.and(
                cb.equal(root.get("tenantId"), libraryId),
                cb.in(root.get("role")).value(com.jotheca.membership.model.LibraryRole.LIBRARY_ADMIN)
                        .value(com.jotheca.membership.model.LibraryRole.LIBRARIAN),
                cb.equal(root.get("status"), MembershipStatus.APPROVED)));
        if (staff > targetLimits.maxStaff()) breached.put("staff",
                java.util.Map.of("current", staff, "limit", targetLimits.maxStaff()));
        long bookCount = books.countByTenantId(libraryId);
        if (bookCount > targetLimits.maxBooks()) breached.put("books",
                java.util.Map.of("current", bookCount, "limit", targetLimits.maxBooks()));
        long branchCount = branches.countByTenantId(libraryId);
        if (branchCount > targetLimits.maxBranches()) breached.put("branches",
                java.util.Map.of("current", branchCount, "limit", targetLimits.maxBranches()));
        long storageBytes = storageUsage.usageOf(libraryId);
        long storageCap = (targetLimits.storageGb() + sub.getStorageAddonGb()) * 1024L * 1024L * 1024L;
        if (storageBytes > storageCap) breached.put("storageBytes",
                java.util.Map.of("current", storageBytes, "limit", storageCap));
        if (!breached.isEmpty()) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "DOWNGRADE_BLOCKED",
                    "Plan " + code + " would orphan existing usage. Reduce usage or pick a larger plan.",
                    breached);
        }

        String fromCode = current == null ? "?" : current.getCode();
        String direction = current == null ? "ASSIGN"
                : target.getPriceMonthlyMinor() > current.getPriceMonthlyMinor() ? "UPGRADE"
                : target.getPriceMonthlyMinor() < current.getPriceMonthlyMinor() ? "DOWNGRADE" : "LATERAL";
        sub.setPlanId(target.getId());
        subscriptions.save(sub);
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("tenantId", libraryId);
        detail.put("from", fromCode);
        detail.put("to", target.getCode());
        detail.put("direction", direction);
        if (req.reason() != null && !req.reason().isBlank()) {
            detail.put("reason", req.reason().trim());
        }
        audit.log(AuditAction.SUBSCRIPTION_CHANGED, "LibrarySubscription", sub.getId(), detail);
        return new PlanChangeDto.PlanChangeResult(libraryId, library.getUid(), fromCode,
                target.getCode(), direction, req.reason());
    }

    @Transactional
    public PlatformLibraryDto approve(String id) {
        return PlatformLibraryDto.from(libraryService.approve(id));
    }

    @Transactional
    public PlatformLibraryDto suspend(String id) {
        return PlatformLibraryDto.from(libraryService.suspend(id));
    }

    @Transactional(readOnly = true)
    public PlatformStatsDto stats() {
        long active = libraries.count((root, q, cb) -> cb.equal(root.get("status"), LibraryStatus.ACTIVE));
        long pending = libraries.count((root, q, cb) -> cb.equal(root.get("status"), LibraryStatus.PENDING_APPROVAL));
        long suspended = libraries.count((root, q, cb) -> cb.equal(root.get("status"), LibraryStatus.SUSPENDED));
        long pendingMemberships = memberships.count((root, q, cb) -> cb.equal(root.get("status"), MembershipStatus.PENDING));
        return new PlatformStatsDto(users.count(), libraries.count(), active, pending, suspended,
                memberships.count(), pendingMemberships);
    }
}
