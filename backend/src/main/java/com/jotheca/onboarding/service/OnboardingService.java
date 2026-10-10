package com.jotheca.onboarding.service;

import com.jotheca.audit.AuditService;
import com.jotheca.audit.model.AuditAction;
import com.jotheca.billing.model.SubscriptionPlan;
import com.jotheca.billing.repo.SubscriptionPlanRepository;
import com.jotheca.billing.service.PaymentService;
import com.jotheca.common.error.ApiException;
import com.jotheca.common.tenant.CodeGenerator;
import com.jotheca.common.web.AuthUser;
import com.jotheca.common.web.SecurityUsers;
import com.jotheca.identity.repo.UserRepository;
import com.jotheca.membership.model.LibraryRole;
import com.jotheca.membership.model.Membership;
import com.jotheca.membership.model.MembershipStatus;
import com.jotheca.membership.model.MembershipType;
import com.jotheca.membership.repo.MembershipRepository;
import com.jotheca.billing.model.LibrarySubscription;
import com.jotheca.billing.repo.LibrarySubscriptionRepository;
import com.jotheca.onboarding.dto.CreateOnboardingRequest;
import com.jotheca.onboarding.dto.OnboardingDto;
import com.jotheca.onboarding.dto.OnboardingSubmitResponse;
import com.jotheca.onboarding.model.OnboardingRequest;
import com.jotheca.onboarding.repo.OnboardingRequestRepository;
import com.jotheca.tenancy.model.JoinPolicy;
import com.jotheca.tenancy.model.Library;
import com.jotheca.tenancy.model.LibraryType;
import com.jotheca.tenancy.repo.LibraryRepository;
import com.jotheca.tenancy.service.LibraryService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Library onboarding pipeline (spec §11):
 * request → plan → payment → PAYMENT_CONFIRMED → SUPER_ADMIN approve → tenant provisioned.
 */
@Service
public class OnboardingService {

    private final OnboardingRequestRepository requests;
    private final SubscriptionPlanRepository plans;
    private final PaymentService paymentService;
    private final LibraryService libraryService;
    private final LibraryRepository libraries;
    private final MembershipRepository memberships;
    private final LibrarySubscriptionRepository subscriptions;
    private final UserRepository users;
    private final AuditService audit;

    public OnboardingService(OnboardingRequestRepository requests, SubscriptionPlanRepository plans,
                             PaymentService paymentService,
                             LibraryService libraryService, LibraryRepository libraries,
                             MembershipRepository memberships, LibrarySubscriptionRepository subscriptions,
                             UserRepository users, AuditService audit) {
        this.requests = requests;
        this.plans = plans;
        this.paymentService = paymentService;
        this.libraryService = libraryService;
        this.libraries = libraries;
        this.memberships = memberships;
        this.subscriptions = subscriptions;
        this.users = users;
        this.audit = audit;
    }

    // ---------- applicant side ----------

    @Transactional
    public OnboardingSubmitResponse submit(CreateOnboardingRequest req) {
        AuthUser me = SecurityUsers.current();
        var user = users.findById(me.id())
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Authentication required"));
        SubscriptionPlan plan = plans.findByCode(req.planCode().trim().toUpperCase(Locale.ROOT))
                .filter(SubscriptionPlan::isActive)
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_PLAN", "Unknown or inactive plan"));
        LibraryType type = LibraryService.parseEnum(LibraryType.class, req.type(), "INVALID_LIBRARY_TYPE");

        OnboardingRequest request = new OnboardingRequest();
        request.setApplicantUserId(user.getId());
        request.setLibraryName(req.libraryName().trim());
        request.setType(type);
        request.setCountry(req.country());
        request.setCity(req.city());
        request.setAddress(req.address());
        request.setDescription(req.description());
        request.setPlanCode(plan.getCode());
        requests.save(request);
        audit.log(AuditAction.ONBOARDING_SUBMITTED, "OnboardingRequest", request.getId(),
                Map.of("libraryName", request.getLibraryName(), "plan", plan.getCode()));

        PaymentService.CheckoutDetails checkout =
                paymentService.initiateSubscriptionPayment(user, request, plan);
        return new OnboardingSubmitResponse(request.getId(), request.getStatus().name(), plan.getCode(),
                checkout.reference(), checkout.authorizationUrl(),
                checkout.amountMinor(), checkout.currency(), checkout.provider());
    }

    @Transactional(readOnly = true)
    public List<OnboardingDto> mine() {
        AuthUser me = SecurityUsers.current();
        List<OnboardingRequest> mine = requests.findByApplicantUserIdOrderByCreatedAtDesc(me.id());
        Map<String, Library> libs = libraries.findAllById(
                mine.stream().map(OnboardingRequest::getLibraryId).filter(java.util.Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(Library::getId, Function.identity()));
        return mine.stream()
                .map(r -> OnboardingDto.from(r,
                        r.getLibraryId() == null ? null : libs.get(r.getLibraryId()) == null ? null : libs.get(r.getLibraryId()).getSlug()))
                .toList();
    }

    // ---------- platform side (SUPER_ADMIN) ----------

    @Transactional(readOnly = true)
    public Page<OnboardingDto> platformList(String status, Pageable pageable) {
        Page<OnboardingRequest> page = (status == null || status.isBlank())
                ? requests.findAll(pageable)
                : requests.findByStatus(LibraryService.parseEnum(OnboardingRequest.Status.class, status, "INVALID_STATUS"), pageable);
        return page.map(r -> OnboardingDto.from(r, librarySlugOf(r)));
    }

    @Transactional
    public OnboardingDto approve(String requestId) {
        OnboardingRequest request = requests.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("REQUEST_NOT_FOUND", "Onboarding request not found"));
        if (request.getStatus() == OnboardingRequest.Status.APPROVED) {
            throw ApiException.conflict("ALREADY_APPROVED", "This request was already approved");
        }
        if (request.getStatus() != OnboardingRequest.Status.PAYMENT_CONFIRMED) {
            throw ApiException.conflict("PAYMENT_REQUIRED",
                    "Payment must be confirmed before a library can be approved");
        }

        // 1. Library tenant created + activated (permanent UID generated here)
        Library library = libraryService.createByPlatform(request.getLibraryName(), request.getType(),
                request.getCountry(), request.getCity(), request.getAddress(), request.getDescription(),
                JoinPolicy.APPROVAL_REQUIRED, true);
        libraryService.approve(library.getId());

        // 2. Applicant becomes the first LIBRARY_ADMIN (spec §11: admin account activated)
        Membership admin = new Membership();
        admin.setTenantId(library.getId());
        admin.setUserId(request.getApplicantUserId());
        admin.setRole(LibraryRole.LIBRARY_ADMIN);
        admin.setMembershipType(MembershipType.STAFF);
        admin.setStatus(MembershipStatus.APPROVED);
        admin.setMembershipNumber(CodeGenerator.membershipNumber());
        admin.setJoinedAt(Instant.now());
        memberships.save(admin);
        library.setMemberCount(1);
        libraries.save(library);

        // 3. Subscription attached — entitlements active from minute one
        SubscriptionPlan plan = plans.findByCode(request.getPlanCode())
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_PLAN", "Plan on request no longer exists"));
        LibrarySubscription subscription = new LibrarySubscription();
        subscription.setTenantId(library.getId());
        subscription.setPlanId(plan.getId());
        subscription.setCurrentPeriodStart(Instant.now());
        subscription.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
        subscriptions.save(subscription);

        request.setStatus(OnboardingRequest.Status.APPROVED);
        request.setLibraryId(library.getId());
        requests.save(request);
        audit.log(AuditAction.ONBOARDING_APPROVED, "OnboardingRequest", request.getId(),
                Map.of("libraryUid", library.getUid(), "slug", library.getSlug(), "plan", plan.getCode()));
        audit.log(AuditAction.SUBSCRIPTION_ACTIVATED, "LibrarySubscription", subscription.getId(),
                Map.of("libraryUid", library.getUid(), "plan", plan.getCode()));
        return OnboardingDto.from(request, library.getSlug());
    }

    @Transactional
    public OnboardingDto reject(String requestId, String reason) {
        OnboardingRequest request = requests.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("REQUEST_NOT_FOUND", "Onboarding request not found"));
        if (request.getStatus() == OnboardingRequest.Status.APPROVED) {
            throw ApiException.conflict("ALREADY_APPROVED", "Approved requests cannot be rejected");
        }
        request.setStatus(OnboardingRequest.Status.REJECTED);
        request.setRejectionReason(reason == null ? "Not specified" : reason.trim());
        requests.save(request);
        audit.log(AuditAction.ONBOARDING_REJECTED, "OnboardingRequest", request.getId(),
                Map.of("reason", request.getRejectionReason()));
        return OnboardingDto.from(request, null);
    }

    private String librarySlugOf(OnboardingRequest r) {
        if (r.getLibraryId() == null) return null;
        return libraries.findById(r.getLibraryId()).map(Library::getSlug).orElse(null);
    }
}
