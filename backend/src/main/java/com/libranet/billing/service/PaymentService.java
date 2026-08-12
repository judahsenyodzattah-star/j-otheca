package com.libranet.billing.service;

import com.libranet.audit.AuditService;
import com.libranet.audit.model.AuditAction;
import com.libranet.billing.model.LibrarySubscription;
import com.libranet.billing.model.Payment;
import com.libranet.billing.model.SubscriptionPlan;
import com.libranet.billing.repo.PaymentRepository;
import com.libranet.common.error.ApiException;
import com.libranet.identity.model.User;
import com.libranet.onboarding.model.OnboardingRequest;
import com.libranet.onboarding.repo.OnboardingRequestRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Orchestrates platform payments through the active {@link PaymentProvider} port. */
@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final OnboardingRequestRepository onboardingRequests;
    private final PaymentProvider provider;
    private final SubscriptionLifecycleService lifecycle;
    private final AuditService audit;

    public PaymentService(PaymentRepository payments, OnboardingRequestRepository onboardingRequests,
                          PaymentProvider provider, SubscriptionLifecycleService lifecycle,
                          AuditService audit) {
        this.payments = payments;
        this.onboardingRequests = onboardingRequests;
        this.provider = provider;
        this.lifecycle = lifecycle;
        this.audit = audit;
    }

    public record CheckoutDetails(String reference, String authorizationUrl, long amountMinor,
                                  String currency, String provider) {}

    @Transactional
    public CheckoutDetails initiateSubscriptionPayment(User payer, OnboardingRequest request, SubscriptionPlan plan) {
        // reuse a still-open checkout if the applicant re-requests one (idempotent feel)
        var existing = payments.findByOnboardingRequestId(request.getId()).stream()
                .filter(p -> p.getStatus() == Payment.Status.INITIATED).findFirst();
        if (existing.isPresent()) {
            Payment p = existing.get();
            return new CheckoutDetails(p.getProviderReference(), extractUrl(p.getMetadata()),
                    p.getAmountMinor(), p.getCurrency(), p.getProvider().name());
        }
        PaymentProvider.Checkout checkout = provider.initiate(
                payer.getEmail(), plan.getPriceMonthlyMinor(), plan.getCurrency(),
                "SUBSCRIPTION", request.getId());
        Payment payment = new Payment();
        payment.setPayerUserId(payer.getId());
        payment.setOnboardingRequestId(request.getId());
        payment.setAmountMinor(plan.getPriceMonthlyMinor());
        payment.setCurrency(plan.getCurrency());
        payment.setPurpose(Payment.Purpose.SUBSCRIPTION);
        payment.setProvider(Payment.Provider.valueOf(provider.code()));
        payment.setProviderReference(checkout.reference());
        payment.setMetadata("{\"authorizationUrl\":\"" + checkout.authorizationUrl()
                + "\",\"plan\":\"" + plan.getCode() + "\"}");
        payments.save(payment);
        audit.log(AuditAction.PAYMENT_INITIATED, "Payment", checkout.reference(),
                Map.of("plan", plan.getCode(), "amountMinor", plan.getPriceMonthlyMinor()));
        return new CheckoutDetails(checkout.reference(), checkout.authorizationUrl(),
                plan.getPriceMonthlyMinor(), plan.getCurrency(), provider.code());
    }

    // ---------- Phase 9: lifecycle + patron rails ----------

    /** Renewal checkout for a tenant's current plan (30-day cycle on confirm). */
    @Transactional
    public CheckoutDetails initiateRenewal(User payer, String tenantId, LibrarySubscription sub, SubscriptionPlan plan) {
        PaymentProvider.Checkout checkout = provider.initiate(
                payer.getEmail(), plan.getPriceMonthlyMinor(), plan.getCurrency(), "SUBSCRIPTION", sub.getId());
        Payment payment = basePayment(payer, checkout, plan.getPriceMonthlyMinor(), plan.getCurrency(),
                Payment.Purpose.SUBSCRIPTION);
        payment.setTenantId(tenantId);
        payment.setMetadata("{\"authorizationUrl\":\"" + checkout.authorizationUrl()
                + "\",\"renewal\":true,\"plan\":\"" + plan.getCode() + "\"}");
        payments.save(payment);
        audit.log(AuditAction.PAYMENT_INITIATED, "Payment", checkout.reference(),
                Map.of("purpose", "SUBSCRIPTION_RENEWAL", "amountMinor", plan.getPriceMonthlyMinor()));
        return new CheckoutDetails(checkout.reference(), checkout.authorizationUrl(),
                plan.getPriceMonthlyMinor(), plan.getCurrency(), provider.code());
    }

    /** Storage add-on checkout ({@code gb} × price-per-GB, stacked onto the plan cap on confirm). */
    @Transactional
    public CheckoutDetails initiateStorageAddon(User payer, String tenantId, int gb, long pricePerGbMinor,
                                                String currency) {
        long amount = (long) gb * pricePerGbMinor;
        PaymentProvider.Checkout checkout = provider.initiate(
                payer.getEmail(), amount, currency, "STORAGE_ADDON", tenantId + ":" + gb);
        Payment payment = basePayment(payer, checkout, amount, currency, Payment.Purpose.STORAGE_ADDON);
        payment.setTenantId(tenantId);
        payment.setMetadata("{\"authorizationUrl\":\"" + checkout.authorizationUrl() + "\",\"gb\":" + gb + "}");
        payments.save(payment);
        audit.log(AuditAction.PAYMENT_INITIATED, "Payment", checkout.reference(),
                Map.of("purpose", "STORAGE_ADDON", "gb", gb, "amountMinor", amount));
        return new CheckoutDetails(checkout.reference(), checkout.authorizationUrl(),
                amount, currency, provider.code());
    }

    /** Patron rail: checkout settling one OPEN fine. Confirmation settles via FineService. */
    @Transactional
    public CheckoutDetails initiateFinePayment(User payer, String tenantId, String fineId,
                                               long amountMinor, String currency) {
        PaymentProvider.Checkout checkout = provider.initiate(
                payer.getEmail(), amountMinor, currency, "FINE", fineId);
        Payment payment = basePayment(payer, checkout, amountMinor, currency, Payment.Purpose.FINE);
        payment.setTenantId(tenantId);
        payment.setFineId(fineId);
        payment.setMetadata("{\"authorizationUrl\":\"" + checkout.authorizationUrl() + "\",\"fineId\":\"" + fineId + "\"}");
        payments.save(payment);
        audit.log(AuditAction.PAYMENT_INITIATED, "Payment", checkout.reference(),
                Map.of("purpose", "FINE", "fineId", fineId, "amountMinor", amountMinor));
        return new CheckoutDetails(checkout.reference(), checkout.authorizationUrl(),
                amountMinor, currency, provider.code());
    }

    private Payment basePayment(User payer, PaymentProvider.Checkout checkout, long amountMinor,
                                String currency, Payment.Purpose purpose) {
        Payment payment = new Payment();
        payment.setPayerUserId(payer.getId());
        payment.setAmountMinor(amountMinor);
        payment.setCurrency(currency);
        payment.setPurpose(purpose);
        payment.setProvider(Payment.Provider.valueOf(provider.code()));
        payment.setProviderReference(checkout.reference());
        return payment;
    }

    /** GBs embedded in a storage add-on payment's metadata (written at initiation time). */
    public int addonGbOf(Payment payment) {
        String metadata = payment.getMetadata();
        if (metadata == null) return 0;
        int i = metadata.indexOf("\"gb\":");
        if (i < 0) return 0;
        int start = i + 5;
        int end = start;
        while (end < metadata.length() && Character.isDigit(metadata.charAt(end))) end++;
        try {
            return Integer.parseInt(metadata.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Confirm a payment from its provider reference. Idempotent. Called by the verified
     * webhook AND by the dev console (mock provider). A server-side verify() lookup precedes
     * any state change — a webhook body alone is never trusted.
     */
    @Transactional
    public Payment confirmByReference(String reference) {
        Payment payment = payments.findByProviderAndProviderReference(
                        Payment.Provider.valueOf(provider.code()), reference)
                .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Unknown payment reference"));
        if (payment.getStatus() == Payment.Status.PAID) {
            return payment; // idempotent
        }
        PaymentProvider.Verification verification = provider.verify(reference);
        if (!verification.paid()) {
            payment.setStatus(Payment.Status.FAILED);
            audit.log(AuditAction.PAYMENT_CONFIRMED, "Payment", reference, Map.of("result", "failed"));
            return payment;
        }
        payment.setStatus(Payment.Status.PAID);
        payment.setPaidAt(verification.paidAt() != null ? verification.paidAt() : Instant.now());
        payments.save(payment);

        if (payment.getOnboardingRequestId() != null) {
            onboardingRequests.findById(payment.getOnboardingRequestId()).ifPresent(req -> {
                if (req.getStatus() == OnboardingRequest.Status.SUBMITTED) {
                    req.setStatus(OnboardingRequest.Status.PAYMENT_CONFIRMED);
                    onboardingRequests.save(req);
                }
            });
        } else if (payment.getPurpose() == Payment.Purpose.SUBSCRIPTION) {
            lifecycle.renewFromPayment(payment);              // extends +30 days, audits SUBSCRIPTION_RENEWED
        } else if (payment.getPurpose() == Payment.Purpose.STORAGE_ADDON) {
            lifecycle.addStorageAddonFromPayment(payment, addonGbOf(payment));
        }
        // FINE payments are settled by FineService.confirmOnlinePayment after this returns PAID.
        audit.log(AuditAction.PAYMENT_CONFIRMED, "Payment", reference,
                Map.of("amountMinor", payment.getAmountMinor(), "channel",
                        verification.channel() == null ? "unknown" : verification.channel()));
        return payment;
    }

    private String extractUrl(String metadata) {
        if (metadata == null) return null;
        int i = metadata.indexOf("\"authorizationUrl\":\"");
        if (i < 0) return null;
        int start = i + "\"authorizationUrl\":\"".length();
        int end = metadata.indexOf('"', start);
        return end < 0 ? null : metadata.substring(start, end);
    }
}
