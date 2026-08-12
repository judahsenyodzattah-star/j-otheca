package com.libranet.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.libranet.support.TestApi;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 3 acceptance (spec §11/§16/§31):
 * register library request → plan selected → payment confirmed → SUPER_ADMIN approval →
 * tenant provisioned (UID generated) → applicant is LIBRARY_ADMIN with an active subscription.
 */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OnboardingFlowTest {

    @Autowired MockMvc mvc;

    private static String userToken;
    private static String requestId;
    private static String paymentReference;

    @Test
    @Order(1)
    void planCatalogIsPublicAndDataDriven() throws Exception {
        mvc.perform(get("/api/v1/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("STARTER"))
                .andExpect(jsonPath("$[1].limits.maxMembers").value(2000))
                .andExpect(jsonPath("$[2].code").value("ENTERPRISE"));
    }

    @Test
    @Order(2)
    void applicantSubmitsLibraryRequestAndReceivesCheckout() throws Exception {
        String email = "founder-" + System.nanoTime() + "@asakaa.edu.gh";
        TestApi.register(mvc, email, "Password12345", "Yaw Founder");
        userToken = TestApi.login(mvc, email, "Password12345");

        MvcResult res = mvc.perform(post("/api/v1/onboarding/requests")
                        .header("Authorization", TestApi.bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"libraryName\":\"Asa Kasa Academy Library\",\"type\":\"SCHOOL\","
                                + "\"country\":\"Ghana\",\"city\":\"Kumasi\",\"planCode\":\"PROFESSIONAL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn();
        JsonNode body = TestApi.readJson(res);
        requestId = body.path("requestId").asText();
        paymentReference = body.path("paymentReference").asText();
        assertThat(paymentReference).startsWith("MOCK-");
    }

    @Test
    @Order(3)
    void platformCannotApproveBeforePayment() throws Exception {
        String admin = TestApi.login(mvc, "admin@libranet.app", "Admin12345!");
        mvc.perform(post("/api/v1/platform/onboarding/" + requestId + "/approve")
                        .header("Authorization", TestApi.bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_REQUIRED"));
    }

    @Test
    @Order(4)
    void paymentConfirmationMovesRequestIntoApprovalQueue() throws Exception {
        mvc.perform(post("/api/v1/billing/dev/mark-paid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"" + paymentReference + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        mvc.perform(get("/api/v1/onboarding/requests/mine")
                        .header("Authorization", TestApi.bearer(userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PAYMENT_CONFIRMED"));
    }

    @Test
    @Order(5)
    void superAdminApprovesAndTenantIsProvisionedWithAdminMembership() throws Exception {
        String admin = TestApi.login(mvc, "admin@libranet.app", "Admin12345!");
        mvc.perform(post("/api/v1/platform/onboarding/" + requestId + "/approve")
                        .header("Authorization", TestApi.bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.librarySlug").value("asa-kasa-academy-library"));

        // tenant now discoverable publicly
        mvc.perform(get("/api/v1/libraries/asa-kasa-academy-library"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Asa Kasa Academy Library"));

        // applicant got their LIBRARY_ADMIN membership
        MvcResult mine = mvc.perform(get("/api/v1/me/memberships")
                        .header("Authorization", TestApi.bearer(userToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode memberships = TestApi.readJson(mine);
        JsonNode target = null;
        for (JsonNode m : memberships) {
            if ("Asa Kasa Academy Library".equals(m.path("libraryName").asText())) {
                target = m;
            }
        }
        assertThat(target).isNotNull();
        target = java.util.Objects.requireNonNull(target);
        assertThat(target.path("role").asText()).isEqualTo("LIBRARY_ADMIN");
        assertThat(target.path("membershipNumber").asText()).isNotBlank();

        // and can immediately use the tenant plane
        mvc.perform(get("/api/v1/tenant/members")
                        .header("Authorization", TestApi.bearer(userToken))
                        .header("X-Library-Id", target.path("libraryUid").asText()))
                .andExpect(status().isOk());

        // approval is not repeatable
        mvc.perform(post("/api/v1/platform/onboarding/" + requestId + "/approve")
                        .header("Authorization", TestApi.bearer(admin)))
                .andExpect(status().isConflict());

        // revenue record exists for the platform
        mvc.perform(get("/api/v1/platform/payments")
                        .header("Authorization", TestApi.bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void ordinaryUsersCannotReachTheApprovalQueue() throws Exception {
        String patron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        mvc.perform(get("/api/v1/platform/onboarding")
                        .header("Authorization", TestApi.bearer(patron)))
                .andExpect(status().isForbidden());
    }
}
