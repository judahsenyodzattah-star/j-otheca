package com.libranet.billing.service;

import java.time.Instant;

/** Port for platform payments (ADR-004). Implementations: Paystack (prod), Mock (dev/tests). */
public interface PaymentProvider {

    record Checkout(String reference, String authorizationUrl) {}

    record Verification(boolean paid, Instant paidAt, String channel) {}

    /** Start a hosted checkout for an amount in minor units. */
    Checkout initiate(String email, long amountMinor, String currency, String purpose, String internalReference);

    /** Server-side truth lookup for a reference (used by webhooks and reconciliation). */
    Verification verify(String reference);

    /** Verify a webhook request signature. Mock provider accepts its own marker. */
    boolean validSignature(String rawBody, String signatureHeader);

    String code();
}
