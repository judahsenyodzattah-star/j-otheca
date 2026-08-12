package com.libranet.billing.service;

import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.billing.model.LibrarySubscription;
import com.libranet.billing.model.Payment;
import com.libranet.billing.repo.LibrarySubscriptionRepository;
import com.libranet.identity.service.MailService;
import com.libranet.membership.model.LibraryRole;
import com.libranet.membership.model.Membership;
import com.libranet.membership.model.MembershipStatus;
import com.libranet.membership.repo.MembershipRepository;
import com.libranet.identity.repo.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subscription lifecycle (Phase 9, spec §31): renewal extension, expiry sweep, storage add-ons.
 * Period arithmetic is fixed 30-day cycles (plans are monthly-priced; annual billing is a
 * catalog entry, not new logic).
 *
 * All mutations run REQUIRES_NEW where the caller may throw afterwards, so lifecycle state
 * is never rolled back by a business flow that continues to fail (learned in P5 expiry sweeps).
 */
@Service
public class SubscriptionLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionLifecycleService.class);
    public static final int PERIOD_DAYS = 30;

    private final LibrarySubscriptionRepository subscriptions;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final MailService mail;
    private final AuditService audit;

    public SubscriptionLifecycleService(LibrarySubscriptionRepository subscriptions,
                                        MembershipRepository memberships, UserRepository users,
                                        MailService mail, AuditService audit) {
        this.subscriptions = subscriptions;
        this.memberships = memberships;
        this.users = users;
        this.mail = mail;
        this.audit = audit;
    }

    /** Post-payment hook (from PaymentService.confirmByReference): extend the period by one cycle. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void renewFromPayment(Payment payment) {
        if (payment.getTenantId() == null) {
            return; // platform sign-up payment — provisioning handles it, not renewals
        }
        subscriptions.findByTenantId(payment.getTenantId()).ifPresent(sub -> {
            Instant base = sub.getCurrentPeriodEnd() != null && sub.getCurrentPeriodEnd().isAfter(Instant.now())
                    ? sub.getCurrentPeriodEnd() : Instant.now();
            sub.setCurrentPeriodEnd(base.plus(PERIOD_DAYS, ChronoUnit.DAYS));
            sub.setStatus(LibrarySubscription.Status.ACTIVE);
            subscriptions.save(sub);
            audit.log(AuditAction.SUBSCRIPTION_RENEWED, "LibrarySubscription", sub.getId(),
                    Map.of("tenantId", sub.getTenantId(), "newPeriodEnd", sub.getCurrentPeriodEnd().toString(),
                            "reference", payment.getProviderReference()));
        });
    }

    /** Post-payment hook: stack purchased storage GBs onto the subscription. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void addStorageAddonFromPayment(Payment payment, int gb) {
        if (payment.getTenantId() == null) {
            return;
        }
        subscriptions.findByTenantId(payment.getTenantId()).ifPresent(sub -> {
            sub.setStorageAddonGb(sub.getStorageAddonGb() + gb);
            subscriptions.save(sub);
            audit.log(AuditAction.STORAGE_ADDON_PURCHASED, "LibrarySubscription", sub.getId(),
                    Map.of("tenantId", sub.getTenantId(), "gb", gb,
                            "totalAddonGb", sub.getStorageAddonGb(),
                            "reference", payment.getProviderReference()));
        });
    }

    /**
     * Lazy expiry sweep (called at the top of EntitlementService.limitsFor): flip an
     * ACTIVE subscription whose period ended to EXPIRED, audit it, and mail the library
     * admins exactly once. Runs REQUIRES_NEW so the read path never fails because of it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireIfOverdue(String subscriptionId) {
        subscriptions.findById(subscriptionId).ifPresent(sub -> {
            if (sub.getStatus() != LibrarySubscription.Status.ACTIVE
                    || sub.getCurrentPeriodEnd() == null
                    || !sub.getCurrentPeriodEnd().isBefore(Instant.now())) {
                return;
            }
            sub.setStatus(LibrarySubscription.Status.EXPIRED);
            subscriptions.save(sub);
            audit.log(AuditAction.SUBSCRIPTION_EXPIRED, "LibrarySubscription", sub.getId(),
                    Map.of("tenantId", sub.getTenantId(), "periodEnded", sub.getCurrentPeriodEnd().toString()));
            mailAdmins(sub.getTenantId(),
                    "Your LibraNet subscription has expired",
                    "The subscription period ended on " + sub.getCurrentPeriodEnd()
                            + ". Entitlements now fall back to conservative limits — renew to restore your plan.");
            log.info("subscription {} (tenant {}) expired", sub.getId(), sub.getTenantId());
        });
    }

    private void mailAdmins(String tenantId, String subject, String body) {
        var admins = memberships.findAll((root, q, cb) -> cb.and(
                cb.equal(root.get("tenantId"), tenantId),
                cb.equal(root.get("role"), LibraryRole.LIBRARY_ADMIN),
                cb.equal(root.get("status"), MembershipStatus.APPROVED)));
        for (Membership admin : admins) {
            users.findById(admin.getUserId()).ifPresent(u ->
                    mail.sendNotification(u.getEmail(), subject, body));
        }
    }
}
