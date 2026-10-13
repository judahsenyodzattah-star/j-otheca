package com.jotheca.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.jotheca.billing.service.PaystackPaymentProvider;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Offline unit tests for Paystack webhook signature verification (audit hardening 2026-10-10).
 *
 * <p>These exercise {@link PaystackPaymentProvider#validSignature} directly — no Spring context,
 * no network — so they prove the cryptographic properties: correct-HMAC acceptance, raw-body
 * binding (tamper rejection), wrong-secret rejection, and fail-closed handling of
 * null/blank/malformed signatures. The provider is only constructed (no HTTP calls are made).
 */
class PaystackSignatureTest {

    private static final String SECRET = "sk_test_unit_secret_1234567890";

    private final PaystackPaymentProvider provider =
            new PaystackPaymentProvider(SECRET, "https://example.test");

    private static String hmacSha512Hex(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("Correct HMAC-SHA512 over the exact raw body is accepted")
    void acceptsValidSignature() throws Exception {
        String body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"REF-1\",\"amount\":5000}}";
        String signature = hmacSha512Hex(SECRET, body);
        assertThat(provider.validSignature(body, signature)).isTrue();
    }

    @Test
    @DisplayName("A body altered after signing is rejected (raw-body binding)")
    void rejectsTamperedBody() throws Exception {
        String body = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"REF-1\"}}";
        String signature = hmacSha512Hex(SECRET, body);
        String tampered = body.replace("REF-1", "REF-2");
        assertThat(provider.validSignature(tampered, signature)).isFalse();
    }

    @Test
    @DisplayName("A signature computed with a different secret is rejected")
    void rejectsWrongSecret() throws Exception {
        String body = "{\"event\":\"charge.success\"}";
        String signature = hmacSha512Hex("sk_test_some_other_secret", body);
        assertThat(provider.validSignature(body, signature)).isFalse();
    }

    @Test
    @DisplayName("Null / blank / malformed signatures fail closed (no exception)")
    void rejectsMalformedSignatures() {
        String body = "{\"event\":\"charge.success\"}";
        assertThat(provider.validSignature(body, null)).isFalse();
        assertThat(provider.validSignature(body, "")).isFalse();
        assertThat(provider.validSignature(body, "   ")).isFalse();
        assertThat(provider.validSignature(body, "not-hex-zzz")).isFalse();
        assertThat(provider.validSignature(body, "abc")).isFalse();   // odd-length hex
        assertThat(provider.validSignature(null, "abcd")).isFalse();  // null body
    }
}
