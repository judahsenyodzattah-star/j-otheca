package com.jotheca.billing.service;

import com.jotheca.common.tenant.CodeGenerator;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/test payment provider: checkout "succeeds" when the dev console marks it paid
 * (/api/v1/billing/dev/mark-paid). Lets the full onboarding→payment→approval loop run offline.
 *
 * <p>Fail-closed (audit 2026-10-10): this fake provider must NEVER be the silent default in a
 * real deployment. It is restricted to the {@code dev}/{@code test} profiles, so staging/prod
 * refuse to boot unless {@code app.payments.provider} is set explicitly (to {@code paystack}).
 */
@Component
@Profile({"dev", "test"})
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "mock", matchIfMissing = true)
public class MockPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentProvider.class);

    @Override
    public Checkout initiate(String email, long amountMinor, String currency, String purpose, String internalReference) {
        String reference = "MOCK-" + CodeGenerator.randomToken(12);
        log.info("[mock-pay] checkout {} for {} — {} {}{}", reference, email, currency, amountMinor / 100.0, "");
        return new Checkout(reference, "/billing/mock-checkout?ref=" + reference);
    }

    @Override
    public Verification verify(String reference) {
        // The mock treats every existing reference as paid at now when explicitly verified after checkout.
        return new Verification(true, Instant.now(), "mock");
    }

    @Override
    public boolean validSignature(String rawBody, String signatureHeader) {
        // The mock has NO authentic webhook sender, so the permitAll Paystack webhook must never
        // authenticate on its behalf. Dev confirms checkouts via /api/v1/billing/dev/mark-paid,
        // which bypasses signature checks entirely. Previously this returned a fixed "mock" marker,
        // which let an unauthenticated caller forge a charge.success webhook and mark ANY payment
        // PAID (free library onboarding / renewals / storage / fine settlement).
        return false;
    }

    @Override
    public String code() {
        return "MOCK";
    }
}
