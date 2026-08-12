package com.libranet.billing.service;

import com.libranet.common.tenant.CodeGenerator;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev/test payment provider: checkout "succeeds" when the dev console marks it paid
 * (/api/v1/billing/dev/mark-paid). Lets the full onboarding→payment→approval loop run offline.
 */
@Component
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
        return "mock".equals(signatureHeader);
    }

    @Override
    public String code() {
        return "MOCK";
    }
}
