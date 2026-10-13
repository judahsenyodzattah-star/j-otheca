package com.jotheca.billing.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Paystack (ADR-004): hosted checkout init, server-side verify, HMAC-SHA512 webhook signature.
 * Activated by app.payments.provider=paystack + PAYSTACK_SECRET_KEY. Never expose the key to clients.
 */
@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "paystack")
public class PaystackPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(PaystackPaymentProvider.class);
    private static final String BASE = "https://api.paystack.co";

    private final RestClient http;
    private final String secretKey;
    private final String callbackBase;

    public PaystackPaymentProvider(@Value("${PAYSTACK_SECRET_KEY:}") String secretKey,
                                   @Value("${app.public-base-url}") String callbackBase) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("app.payments.provider=paystack requires PAYSTACK_SECRET_KEY");
        }
        this.secretKey = secretKey;
        this.callbackBase = callbackBase;
        this.http = RestClient.builder()
                .baseUrl(BASE)
                .defaultHeader("Authorization", "Bearer " + secretKey)
                .build();
    }

    @Override
    public Checkout initiate(String email, long amountMinor, String currency, String purpose, String internalReference) {
        InitiateResponse res = http.post()
                .uri("/transaction/initialize")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new InitiateRequest(email, amountMinor, currency, internalReference,
                        callbackBase + "/billing/return",
                        java.util.Map.of("purpose", purpose, "internalReference", internalReference)))
                .retrieve()
                .body(InitiateResponse.class);
        if (res == null || res.data() == null) {
            throw new IllegalStateException("Paystack initialize returned no data");
        }
        return new Checkout(res.data().reference(), res.data().authorization_url());
    }

    @Override
    public Verification verify(String reference) {
        VerifyResponse res = http.get()
                .uri("/transaction/verify/" + reference)
                .retrieve()
                .body(VerifyResponse.class);
        if (res == null || res.data() == null) {
            return new Verification(false, null, null);
        }
        boolean paid = "success".equalsIgnoreCase(res.data().status());
        Instant paidAt = paid && res.data().paid_at() != null ? Instant.parse(res.data().paid_at()) : null;
        return new Verification(paid, paidAt, res.data().channel());
    }

    @Override
    public boolean validSignature(String rawBody, String signatureHeader) {
        if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        try {
            // HMAC-SHA512 over the EXACT raw request body (never a re-serialized form), keyed by
            // the secret. Raw-body binding is what stops a tampered payload from verifying.
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] expected = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            byte[] provided;
            try {
                // Paystack sends lowercase hex. Normalize before parsing so a malformed header
                // (non-hex or odd-length) fails CLOSED instead of throwing.
                provided = HexFormat.of().parseHex(signatureHeader.trim().toLowerCase(Locale.ROOT));
            } catch (NumberFormatException malformed) {
                return false;
            }
            // Constant-time comparison — never leak how many leading bytes matched (CWE-208).
            return MessageDigest.isEqual(expected, provided);
        } catch (Exception e) {
            log.warn("signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String code() {
        return "PAYSTACK";
    }

    private record InitiateRequest(String email, long amount, String currency, String reference,
                                   String callback_url, java.util.Map<String, String> metadata) {}
    private record InitiateResponse(Boolean status, InitiateData data) {
        record InitiateData(String authorization_url, String reference) {}
    }
    private record VerifyResponse(Boolean status, VerifyData data) {
        record VerifyData(String status, String paid_at, String channel) {}
    }
}
