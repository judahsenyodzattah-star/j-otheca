package com.jotheca.billing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression guard for the payment fail-open defect (security audit 2026-10-10).
 *
 * <p>The Paystack webhook is {@code permitAll}, so its signature check is the ONLY thing standing
 * between an anonymous caller and marking a payment PAID. The dev/test mock provider has no real
 * webhook sender, so it must not authenticate webhook requests at all — dev confirms checkouts via
 * {@code /api/v1/billing/dev/mark-paid}. Before the fix the mock accepted a fixed {@code "mock"}
 * signature marker, so a forged {@code charge.success} body passed the signature check.
 */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PaymentWebhookSecurityTest {

    @Autowired MockMvc mvc;

    private static final String CHARGE_SUCCESS =
            "{\"event\":\"charge.success\",\"data\":{\"reference\":\"MOCK-forged-reference\"}}";

    @Test
    @DisplayName("Forged mock webhook signature is rejected with 401, never confirmed")
    void forgedSignatureIsRejected() throws Exception {
        mvc.perform(post("/api/v1/billing/webhooks/paystack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-paystack-signature", "mock")
                        .content(CHARGE_SUCCESS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_SIGNATURE"));
    }

    @Test
    @DisplayName("Missing webhook signature is rejected with 401")
    void missingSignatureIsRejected() throws Exception {
        mvc.perform(post("/api/v1/billing/webhooks/paystack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CHARGE_SUCCESS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_SIGNATURE"));
    }
}
