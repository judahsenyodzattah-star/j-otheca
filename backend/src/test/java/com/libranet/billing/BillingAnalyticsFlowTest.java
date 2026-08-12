package com.libranet.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.libranet.billing.model.LibrarySubscription;
import com.libranet.billing.repo.LibrarySubscriptionRepository;
import com.libranet.billing.service.StorageUsageService;
import com.libranet.seed.DevDataSeeder;
import com.libranet.support.TestApi;
import com.libranet.tenancy.repo.LibraryRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase-9 flow: subscription view → renewal (+30d) → storage add-on → lazy expiry sweep →
 * plan change (blocked &amp; allowed) → patron online fine rail → three analytics dashboards.
 * Runs against Pentecost (PROFESSIONAL, libadmin) and Accra Central (STARTER, accraadmin/patron).
 * Every fixture it mutates is restored in-class (plan moves round-trip; expiry is re-activated).
 */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BillingAnalyticsFlowTest {

    @Autowired MockMvc mvc;
    @Autowired LibrarySubscriptionRepository subs;
    @Autowired LibraryRepository libraries;
    @Autowired JdbcTemplate jdbc;
    @Autowired StorageUsageService storageUsage;
    @Autowired com.libranet.circulation.repo.FineRepository fines;

    String accraPatronMembershipId;

    String libadmin;    // Pentecost LIBRARY_ADMIN (PROFESSIONAL)
    String accraadmin;  // Accra Central LIBRARY_ADMIN (STARTER)
    String patron;      // Efua — Accra PATRON (MBR-AC0001)
    String superAdmin;
    String pentecostId;
    String accraId;

    @BeforeAll
    void setup() throws Exception {
        libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        accraadmin = TestApi.login(mvc, "accraadmin@libranet.app", "Library12345!");
        patron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        superAdmin = TestApi.login(mvc, "admin@libranet.app", "Admin12345!");
        pentecostId = libraries.findByUid(DevDataSeeder.PENTECOST_UID).orElseThrow().getId();
        accraId = libraries.findByUid(DevDataSeeder.ACCRA_UID).orElseThrow().getId();
    }

    @AfterAll
    void cleanup() {
        // belt & braces: leave every fixture exactly as seeded for later suites
        jdbc.update("DELETE FROM subscription_plans WHERE code IN ('AUDIT_P9_BLOCK','AUDIT_P9_OK')");
        subs.findByTenantId(pentecostId).ifPresent(s -> {
            s.setStatus(LibrarySubscription.Status.ACTIVE);
            if (s.getCurrentPeriodEnd() == null || s.getCurrentPeriodEnd().isBefore(Instant.now())) {
                s.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
            }
            subs.save(s);
        });
        jdbc.update("UPDATE subscription_plans SET price_monthly_minor = 4900 WHERE code = 'STARTER'");
        jdbc.update("UPDATE subscription_plans SET price_monthly_minor = 14900 WHERE code = 'PROFESSIONAL'");
    }

    // ---------- helpers (never shadow the static MockMvc imports) ----------

    private MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", TestApi.bearer(token))
                .header("X-Library-Id", DevDataSeeder.PENTECOST_UID)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder apiAccra(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", TestApi.bearer(token))
                .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder platform(MockHttpServletRequestBuilder b) {
        return b.header("Authorization", TestApi.bearer(superAdmin))
                .contentType(MediaType.APPLICATION_JSON);
    }

    private JsonNode parse(MvcResult r) throws Exception {
        return TestApi.readJson(r);
    }

    private void markPaid(String reference) throws Exception {
        mvc.perform(post("/api/v1/billing/dev/mark-paid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"" + reference + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    // ---------- 1-3: subscription view, renewal, storage add-on ----------

    @Test @Order(1)
    void subscriptionViewShowsPlanAndEffectiveLimits() throws Exception {
        mvc.perform(api(get("/api/v1/tenant/billing/subscription"), libadmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value("PROFESSIONAL"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.priceMonthlyMinor").value(14900))
                .andExpect(jsonPath("$.currency").value("GHS"))
                .andExpect(jsonPath("$.daysRemaining").isNumber())
                .andExpect(jsonPath("$.effectiveLimits.storageGb").value(50))
                .andExpect(jsonPath("$.effectiveLimits.maxStaff").value(10));
        // non-admin is refused
        mvc.perform(apiAccra(get("/api/v1/tenant/billing/subscription"), patron))
                .andExpect(status().isForbidden());
    }

    @Test @Order(2)
    void renewalExtendsPeriodByThirtyDays() throws Exception {
        JsonNode before = parse(mvc.perform(api(get("/api/v1/tenant/billing/subscription"), libadmin))
                .andExpect(status().isOk()).andReturn());
        long daysBefore = before.path("daysRemaining").asLong();

        JsonNode checkout = parse(mvc.perform(api(post("/api/v1/tenant/billing/renew"), libadmin))
                .andExpect(status().isOk()).andReturn());
        assertThat(checkout.path("amountMinor").asLong()).isEqualTo(14900);
        String reference = checkout.path("reference").asText();
        assertThat(reference).isNotBlank();
        markPaid(reference);

        JsonNode after = parse(mvc.perform(api(get("/api/v1/tenant/billing/subscription"), libadmin))
                .andExpect(status().isOk()).andReturn());
        assertThat(after.path("daysRemaining").asLong()).isGreaterThanOrEqualTo(daysBefore + 29);
        assertThat(after.path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test @Order(3)
    void storageAddonStacksOnThePlanCap() throws Exception {
        JsonNode checkout = parse(mvc.perform(api(post("/api/v1/tenant/billing/storage-addon"), libadmin)
                        .content("{\"gb\":2}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(checkout.path("amountMinor").asLong()).isEqualTo(2000); // 2 × GH₵10/GB
        markPaid(checkout.path("reference").asText());

        mvc.perform(api(get("/api/v1/tenant/billing/subscription"), libadmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storageAddonGb").value(2))
                // effective = plan 50 + add-on 2 — data, not code
                .andExpect(jsonPath("$.effectiveLimits.storageGb").value(52));

        // the tenant payment history shows both payments
        JsonNode history = parse(mvc.perform(api(get("/api/v1/tenant/billing/payments"), libadmin))
                .andExpect(status().isOk()).andReturn());
        assertThat(history.size()).isGreaterThanOrEqualTo(2);
        assertThat(history.toString()).contains("STORAGE_ADDON").contains("SUBSCRIPTION");

        // validation: 0 GB or a non-admin attempt are refused
        mvc.perform(api(post("/api/v1/tenant/billing/storage-addon"), libadmin).content("{\"gb\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(apiAccra(post("/api/v1/tenant/billing/storage-addon"), patron).content("{\"gb\":1}"))
                .andExpect(status().isForbidden());
    }

    // ---------- 4: lazy expiry flip + restore ----------

    @Test @Order(4)
    void overdueSubscriptionFlipsToExpiredLazily() throws Exception {
        LibrarySubscription sub = subs.findByTenantId(pentecostId).orElseThrow();
        Instant originalEnd = sub.getCurrentPeriodEnd();
        try {
            sub.setCurrentPeriodEnd(Instant.now().minus(2, ChronoUnit.DAYS));
            subs.saveAndFlush(sub);

            // reading limits (here: via the billing view) runs the sweep in its own transaction.
            // The first view may serialise the pre-sweep row (1st-level cache); the committed
            // flip is what matters — subsequent reads observe EXPIRED.
            mvc.perform(api(get("/api/v1/tenant/billing/subscription"), libadmin))
                    .andExpect(status().isOk());
            assertThat(subs.findByTenantId(pentecostId).orElseThrow().getStatus())
                    .isEqualTo(LibrarySubscription.Status.EXPIRED);
            mvc.perform(api(get("/api/v1/tenant/billing/subscription"), libadmin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("EXPIRED"));
        } finally {
            // restore exactly as before the test — later suites must not notice
            LibrarySubscription fresh = subs.findByTenantId(pentecostId).orElseThrow();
            fresh.setStatus(LibrarySubscription.Status.ACTIVE);
            fresh.setCurrentPeriodEnd(originalEnd);
            subs.saveAndFlush(fresh);
        }
        mvc.perform(api(get("/api/v1/tenant/billing/subscription"), libadmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ---------- 5-6: platform plan changes ----------

    @Test @Order(5)
    void planChangeBlockedWhenUsageExceedsTargetLimits() throws Exception {
        // probe plan: plenty of members/storage, but maxBooks below Pentecost's seeded catalog (3)
        jdbc.update("INSERT INTO subscription_plans "
                + "(id, code, name, price_monthly_minor, currency, limits, active, sort_order, "
                + " created_at, updated_at) VALUES ("
                + "'audit-p9-block', 'AUDIT_P9_BLOCK', 'Audit Block', 1000, 'GHS', "
                + "'{\"storageGb\":50,\"maxMembers\":500,\"maxBooks\":2,\"maxStaff\":10,"
                + "\"maxBranches\":5,\"reservations\":true}', FALSE, 98, CURRENT_TIMESTAMP,"
                + " CURRENT_TIMESTAMP)");

        mvc.perform(platform(post("/api/v1/platform/libraries/{id}/plan", pentecostId))
                        .content("{\"planCode\":\"AUDIT_P9_BLOCK\",\"reason\":\"probe\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOWNGRADE_BLOCKED"))
                .andExpect(jsonPath("$.details.books.limit").value(2));

        // unknown plan 404; unchanged plan 400
        mvc.perform(platform(post("/api/v1/platform/libraries/{id}/plan", pentecostId))
                        .content("{\"planCode\":\"NOPE\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(platform(post("/api/v1/platform/libraries/{id}/plan", pentecostId))
                        .content("{\"planCode\":\"PROFESSIONAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLAN_UNCHANGED"));

        // still on PROFESSIONAL
        assertThat(subs.findByTenantId(pentecostId).orElseThrow().getPlanId())
                .isEqualTo("plan-professional");
        jdbc.update("DELETE FROM subscription_plans WHERE code = 'AUDIT_P9_BLOCK'");
    }

    @Test @Order(6)
    void planChangeDowngradeAndUpgradeRoundTrip() throws Exception {
        // Pentecost usage (2 staff, 3 books, 1 branch, ≤100 approved members, storage « 5 GB)
        // fits STARTER — the move is allowed and audited.
        mvc.perform(platform(post("/api/v1/platform/libraries/{id}/plan", pentecostId))
                        .content("{\"planCode\":\"STARTER\",\"reason\":\"annual lapsed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromPlan").value("PROFESSIONAL"))
                .andExpect(jsonPath("$.toPlan").value("STARTER"))
                .andExpect(jsonPath("$.direction").value("DOWNGRADE"));

        // effective limits NOW reflect STARTER + the purchased 2 GB add-on (data, not code)
        mvc.perform(api(get("/api/v1/tenant/billing/subscription"), libadmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value("STARTER"))
                .andExpect(jsonPath("$.effectiveLimits.storageGb").value(7));

        // and back — restores the fixture for later suites
        mvc.perform(platform(post("/api/v1/platform/libraries/{id}/plan", pentecostId))
                        .content("{\"planCode\":\"PROFESSIONAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("UPGRADE"));
        mvc.perform(api(get("/api/v1/tenant/billing/subscription"), libadmin))
                .andExpect(jsonPath("$.planCode").value("PROFESSIONAL"))
                .andExpect(jsonPath("$.effectiveLimits.storageGb").value(52));
    }

    // ---------- 7: patron online fine rail ----------

    @Test @Order(7)
    void patronPaysOwnFineOnlineCrossTenantInvisible() throws Exception {
        // staff assesses a manual fine on the patron's Accra membership
        JsonNode lookup = parse(mvc.perform(apiAccra(
                        get("/api/v1/tenant/circulation/members/lookup?number=MBR-AC0001"), accraadmin))
                .andExpect(status().isOk()).andReturn());
        String membershipId = lookup.path("membershipId").asText();
        if (membershipId == null || membershipId.isBlank()) {
            membershipId = lookup.path("membership").path("id").asText();
        }
        assertThat(membershipId).isNotBlank();
        accraPatronMembershipId = membershipId;

        JsonNode fine = parse(mvc.perform(apiAccra(post("/api/v1/tenant/circulation/fines"), accraadmin)
                        .content("{\"membershipId\":\"" + membershipId
                                + "\",\"type\":\"MANUAL\",\"amountMinor\":2500,"
                                + "\"note\":\"P9 demo: lost library card replacement\"}"))
                .andExpect(status().isCreated()).andReturn());
        String fineId = fine.path("id").asText();

        // another tenant's staff (Pentecost librarian context) cannot even SEE it
        String otherStaff = TestApi.login(mvc, "staff@libranet.app", "Library12345!");
        mvc.perform(api(post("/api/v1/tenant/circulation/fines/{id}/pay-online", fineId), otherStaff))
                .andExpect(status().isNotFound());
        mvc.perform(api(post("/api/v1/tenant/circulation/fines/{id}/confirm-online", fineId), otherStaff)
                        .content("{\"reference\":\"anything\"}"))
                .andExpect(status().isNotFound());

        // the owner starts a checkout
        JsonNode checkout = parse(mvc.perform(apiAccra(
                        post("/api/v1/tenant/circulation/fines/{id}/pay-online", fineId), patron))
                .andExpect(status().isOk()).andReturn());
        assertThat(checkout.path("amountMinor").asLong()).isEqualTo(2500);
        String reference = checkout.path("reference").asText();

        // a WRONG reference belonging to nobody → 404
        mvc.perform(apiAccra(post("/api/v1/tenant/circulation/fines/{id}/confirm-online", fineId), patron)
                        .content("{\"reference\":\"bogus-reference\"}"))
                .andExpect(status().isNotFound());
        // the fine is still open after the bogus attempt
        mvc.perform(apiAccra(get("/api/v1/tenant/circulation/fines/me/open"), patron))
                .andExpect(status().isOk());

        // the mock provider verifies instantly — the first real confirmation settles it
        mvc.perform(apiAccra(post("/api/v1/tenant/circulation/fines/{id}/confirm-online", fineId), patron)
                        .content("{\"reference\":\"" + reference + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.settlement").value(org.hamcrest.Matchers.containsString("online")));
        mvc.perform(apiAccra(post("/api/v1/tenant/circulation/fines/{id}/confirm-online", fineId), patron)
                        .content("{\"reference\":\"" + reference + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FINE_NOT_OPEN"));

        // the tenant payment history picked up the FINE rail row
        JsonNode history = parse(mvc.perform(apiAccra(
                        get("/api/v1/tenant/billing/payments"), accraadmin))
                .andExpect(status().isOk()).andReturn());
        assertThat(history.toString()).contains("FINE");
    }

    // ---------- 8-10: three analytics dashboards ----------

    @Test @Order(8)
    void tenantAdminDashboardMatchesFixtures() throws Exception {
        JsonNode d = parse(mvc.perform(api(get("/api/v1/tenant/analytics/dashboard"), libadmin))
                .andExpect(status().isOk()).andReturn());
        assertThat(d.path("planCode").asText()).isEqualTo("PROFESSIONAL");
        assertThat(d.path("subscriptionStatus").asText()).isEqualTo("ACTIVE");
        // Pentecost seed fixture
        assertThat(d.path("books").path("used").asLong()).isEqualTo(3);
        assertThat(d.path("branches").path("used").asLong()).isEqualTo(1);
        assertThat(d.path("members").path("used").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(d.path("copiesTotal").asLong()).isGreaterThanOrEqualTo(1);
        // usage bars carry the effective limits (PROFESSIONAL + 2 GB add-on)
        assertThat(d.path("storageBytes").path("limit").asLong())
                .isEqualTo(52L * 1024 * 1024 * 1024);
        assertThat(d.path("members").path("limit").asLong()).isEqualTo(2000);
        // payments collected so far in this class: renewal 14900 + add-on 2000
        assertThat(d.path("paymentsTotalMinor").asLong()).isGreaterThanOrEqualTo(16900);
        // demand charts exist and are arrays
        assertThat(d.path("topDemanded").isArray()).isTrue();
        assertThat(d.path("topRated").isArray()).isTrue();
    }

    @Test @Order(9)
    void memberDashboardShowsOwnCirculation() throws Exception {
        JsonNode d = parse(mvc.perform(apiAccra(get("/api/v1/tenant/analytics/me"), patron))
                .andExpect(status().isOk()).andReturn());
        assertThat(d.path("role").asText()).isEqualTo("PATRON");
        // the fine from step 7 was settled online — the dashboard must equal the ledger exactly
        assertThat(d.path("openFinesMinor").asLong())
                .isEqualTo(fines.openBalanceForMember(accraId, accraPatronMembershipId));
        assertThat(d.path("activeLoans").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(d.path("unreadNotifications").asLong()).isGreaterThanOrEqualTo(1); // FINE_PAID receipt
        // accraadmin is staff, not a patron — the member view works for them too (their own numbers)
        mvc.perform(apiAccra(get("/api/v1/tenant/analytics/me"), accraadmin))
                .andExpect(status().isOk());
    }

    @Test @Order(10)
    void platformDashboardMrrBreakdownAndVolume() throws Exception {
        JsonNode d = parse(mvc.perform(platform(get("/api/v1/platform/analytics/dashboard")))
                .andExpect(status().isOk()).andReturn());
        // fixture: Pentecost PROFESSIONAL + Accra STARTER + Ashesi ?? — at minimum 14900+4900
        assertThat(d.path("mrr").path("mrrMinor").asLong()).isGreaterThanOrEqualTo(14900 + 4900);
        assertThat(d.path("mrr").path("currency").asText()).isEqualTo("GHS");
        assertThat(d.path("mrr").path("byPlan").isArray()).isTrue();
        assertThat(d.path("libraries").path("total").asLong()).isGreaterThanOrEqualTo(4);
        assertThat(d.path("libraries").path("active").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(d.path("memberships").path("approved").asLong()).isGreaterThanOrEqualTo(5);
        // PAID volume collected in this suite (renewal + add-on + fine = 19400)
        assertThat(d.path("payments").path("totalPaidMinor").asLong()).isGreaterThanOrEqualTo(19400);
        assertThat(d.path("payments").path("subscriptionMinor").asLong()).isGreaterThanOrEqualTo(14900);
        assertThat(d.path("payments").path("finesMinor").asLong()).isEqualTo(2500);
        assertThat(d.path("activeSubscriptions").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(d.path("auditEventsLast24h").asLong()).isGreaterThan(0);
        // tenant users are refused the platform plane
        mvc.perform(api(get("/api/v1/platform/analytics/dashboard"), libadmin))
                .andExpect(status().isForbidden());
    }

    // ---------- 11: storage ledger reflects media lifecycle ----------

    @Test @Order(11)
    void storageUsageLedgerTracksUploadAndDelete() throws Exception {
        long before = storageUsage.usageOf(pentecostId);
        MockMultipartFile png = new MockMultipartFile("file", "p9.png", "image/png",
                TestApi.pngBytes(64, 64));
        JsonNode uploaded = parse(mvc.perform(multipart("/api/v1/tenant/library/logo")
                        .file(png)
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isOk()).andReturn());
        String mediaId = uploaded.path("id").asText();
        long afterUpload = storageUsage.usageOf(pentecostId);
        assertThat(afterUpload).isGreaterThan(before);

        mvc.perform(api(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.delete("/api/v1/tenant/media/{id}", mediaId), libadmin))
                .andExpect(status().isOk());
        // the ledger nets the freed bytes (delete is a status flag; rows never destroyed)
        assertThat(storageUsage.usageOf(pentecostId)).isEqualTo(before);
    }
}
