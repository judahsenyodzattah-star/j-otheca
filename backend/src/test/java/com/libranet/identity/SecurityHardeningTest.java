package com.libranet.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.libranet.identity.security.Totp;
import com.libranet.seed.DevDataSeeder;
import com.libranet.support.TestApi;
import com.libranet.tenancy.repo.LibraryRepository;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase-8 hardening flows: durable login lockout, sessions/devices management,
 * real RFC-6238 TOTP MFA (enroll→enable→challenge→verify→disable), audit viewers,
 * and the reviewed response headers. Fresh users isolate counters from other suites.
 */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityHardeningTest {

    @Autowired MockMvc mvc;
    @Autowired LibraryRepository libraries;

    private JsonNode postJson(String url, String json) throws Exception {
        MvcResult result = mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn();
        return TestApi.readJson(result);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", TestApi.bearer(token));
    }

    // ---------- lockout ----------

    @Test @Order(1)
    void repeatedFailuresLockTheAccountDurably() throws Exception {
        TestApi.register(mvc, "p8lock@libranet.app", "Lock12345!", "Lock Test");
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"p8lock@libranet.app\",\"password\":\"wrong-\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        }
        // 10 failures armed the lock: now even the CORRECT password answers 423
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"p8lock@libranet.app\",\"password\":\"Lock12345!\"}"))
                .andExpect(status().is(423))
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.details.retryAfterSeconds").isNumber());
    }

    @Test @Order(2)
    void unknownEmailHasNoLockoutOracle() throws Exception {
        // many failures for a non-existent email must look EXACTLY like a bad password (enumeration)
        for (int i = 0; i < 12; i++) {
            mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"p8ghost@libranet.app\",\"password\":\"whatever1\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        }
    }

    // ---------- sessions / devices ----------

    @Test @Order(3)
    void sessionsListCurrentDeviceSupportsTargetedRevoke() throws Exception {
        TestApi.register(mvc, "p8sess@libranet.app", "Sess12345!", "Session Test");
        JsonNode laptop = postJson("/api/v1/auth/login", "{\"email\":\"p8sess@libranet.app\",\"password\":\"Sess12345!\"}");
        JsonNode phone = TestApi.readJson(mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "okhttp/4.12.0")
                        .content("{\"email\":\"p8sess@libranet.app\",\"password\":\"Sess12345!\"}"))
                .andReturn());
        String token = laptop.path("accessToken").asText();

        MvcResult list = mvc.perform(authed(get("/api/v1/me/security/sessions"), token)
                        .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) Firefox/121.0"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode sessions = TestApi.readJson(list);
        assertThat(sessions.size()).isGreaterThanOrEqualTo(2);
        // current device is marked from the access token's sid claim
        long current = 0;
        String phoneId = null;
        for (JsonNode s : sessions) {
            if (s.path("current").asBoolean()) current++;
            if ("LibraNet Android app".equals(s.path("deviceInfo").asText())) phoneId = s.path("id").asText();
        }
        assertThat(current).isEqualTo(1);
        assertThat(phoneId).isNotNull();

        // revoking the phone kills ITS refresh token immediately
        mvc.perform(authed(delete("/api/v1/me/security/sessions/" + phoneId), token))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + phone.path("refreshToken").asText() + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REUSE_DETECTED"));
        // revoke-all signs out everywhere
        mvc.perform(authed(post("/api/v1/me/security/sessions/revoke-all"), token))
                .andExpect(status().isNoContent());
        mvc.perform(authed(get("/api/v1/me/security/sessions"), token))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------- TOTP MFA ----------

    @Test @Order(4)
    void totpEnrollmentChallengeAndDisable() throws Exception {
        TestApi.register(mvc, "p8mfa@libranet.app", "MfaPass123!", "Mfa Test");
        JsonNode firstLogin = postJson("/api/v1/auth/login", "{\"email\":\"p8mfa@libranet.app\",\"password\":\"MfaPass123!\"}");
        String token = firstLogin.path("accessToken").asText();
        assertThat(firstLogin.path("mfaRequired").isMissingNode()).isTrue();

        JsonNode enroll = TestApi.readJson(mvc.perform(authed(post("/api/v1/me/security/mfa/enroll"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUri").isString())
                .andReturn());
        String secret = enroll.path("secret").asText();
        assertThat(enroll.path("otpauthUri").asText()).startsWith("otpauth://totp/LibraNet:");
        // not enabled until a code proves possession
        mvc.perform(authed(get("/api/v1/me/security/mfa"), token))
                .andExpect(jsonPath("$.mfaEnabled").value(false));
        String wrong = "000000".equals(currentCode(secret)) ? "000001" : "000000";
        mvc.perform(authed(post("/api/v1/me/security/mfa/enable"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + wrong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MFA_CODE_INVALID"));
        mvc.perform(authed(post("/api/v1/me/security/mfa/enable"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + currentCode(secret) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(true));

        // login now stops at the challenge — no session tokens in the response
        JsonNode challenge = TestApi.readJson(mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"p8mfa@libranet.app\",\"password\":\"MfaPass123!\"}"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(challenge.path("mfaRequired").asBoolean()).isTrue();
        assertThat(challenge.path("refreshToken").isMissingNode()).isTrue();
        String mfaToken = challenge.path("mfaToken").asText();

        // the challenge token is unusable as an access token (typ=mfa ≠ access)
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + mfaToken))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"" + mfaToken + "\",\"code\":\"" + wrong + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MFA_CODE_INVALID"));
        JsonNode verified = TestApi.readJson(mvc.perform(post("/api/v1/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"" + mfaToken + "\",\"code\":\"" + currentCode(secret) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andReturn());

        // disable needs BOTH password and code
        mvc.perform(authed(post("/api/v1/me/security/mfa/disable"), verified.path("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"not-the-password\",\"code\":\"" + currentCode(secret) + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(authed(post("/api/v1/me/security/mfa/disable"), verified.path("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"MfaPass123!\",\"code\":\"" + currentCode(secret) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(false));
        // login is single-factor again
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"p8mfa@libranet.app\",\"password\":\"MfaPass123!\"}"))
                .andExpect(jsonPath("$.accessToken").isString());
    }

    // ---------- audit viewers ----------

    @Test @Order(5)
    void tenantAuditIsAdminOnlyAndTenantPinned() throws Exception {
        String accraAdmin = TestApi.login(mvc, "accraadmin@libranet.app", "Library12345!");
        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        String patron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        String accraId = libraries.findByUid(DevDataSeeder.ACCRA_UID).orElseThrow().getId();
        String pentecostId = libraries.findByUid(DevDataSeeder.PENTECOST_UID).orElseThrow().getId();

        // patrons never read the audit trail
        mvc.perform(get("/api/v1/tenant/audit")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_LIBRARY_ROLE"));

        // both admins produce a fresh event in their own tenant
        for (var entry : new String[][] {{accraAdmin, DevDataSeeder.ACCRA_UID},
                                         {libadmin, DevDataSeeder.PENTECOST_UID}}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/v1/tenant/review-policy")
                            .header("Authorization", TestApi.bearer(entry[0]))
                            .header("X-Library-Id", entry[1])
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"allowAnonymousReviews\":" + entry[1].equals(DevDataSeeder.ACCRA_UID) + "}"))
                    .andExpect(status().isOk());
        }

        // tenant viewer: every row belongs to the caller's tenant — always
        JsonNode accraEvents = TestApi.readJson(mvc.perform(get(
                        "/api/v1/tenant/audit?action=REVIEW_POLICY_UPDATED")
                        .header("Authorization", TestApi.bearer(accraAdmin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk()).andReturn());
        assertThat(accraEvents.path("content").size()).isGreaterThan(0);
        accraEvents.path("content").forEach(e ->
                assertThat(e.path("tenantId").asText()).isEqualTo(accraId));

        JsonNode pentecostEvents = TestApi.readJson(mvc.perform(get(
                        "/api/v1/tenant/audit?action=REVIEW_POLICY_UPDATED")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isOk()).andReturn());
        assertThat(pentecostEvents.path("content").size()).isGreaterThan(0);
        pentecostEvents.path("content").forEach(e ->
                assertThat(e.path("tenantId").asText()).isEqualTo(pentecostId));
    }

    @Test @Order(6)
    void platformAuditIsSuperAdminOnly() throws Exception {
        String admin = TestApi.login(mvc, "admin@libranet.app", "Admin12345!");
        String accraAdmin = TestApi.login(mvc, "accraadmin@libranet.app", "Library12345!");
        mvc.perform(get("/api/v1/platform/audit?action=LOGIN_SUCCESS")
                        .header("Authorization", TestApi.bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("LOGIN_SUCCESS"));
        mvc.perform(get("/api/v1/platform/audit")
                        .header("Authorization", TestApi.bearer(accraAdmin)))
                .andExpect(status().isForbidden());
    }

    // ---------- response headers ----------

    @Test @Order(7)
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/api/v1/plans"))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
                .andExpect(header().string("Permissions-Policy",
                        "camera=(), microphone=(), payment=(), usb=(), bluetooth=()"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    private static String currentCode(String secret) {
        return Totp.hotp(secret, System.currentTimeMillis() / 1000 / 30);
    }
}
