package com.libranet.circulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.libranet.seed.DevDataSeeder;
import com.libranet.support.TestApi;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 5 circulation suite (Pentecost = PROFESSIONAL plan): policy → desk issue/return → renewals →
 * late fine → settle/waive → reservation lifecycle RESERVED→WAITING→READY→COLLECTED→(loan)→return,
 * queue promotion, renewal block, expiry sweep, plan feature gating (Accra STARTER: no reservations).
 */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CirculationFlowTest {

    @Autowired MockMvc mvc;

    private String admin;      // LIBRARY_ADMIN pentecost
    private String staff;      // LIBRARIAN pentecost
    private String memberAToken;
    private String memberAMembershipId;
    private String memberACard;
    private String memberBToken;
    private String memberBCard;
    private String dbConceptsId; // book with copies PU-000001/2
    private String osConceptsId; // book with copy PU-000003

    private JsonNode call(String path, String token, String tenantUid, String body, int expect) throws Exception {
        var req = post(path).header("Authorization", TestApi.bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body == null ? "{}" : body);
        if (tenantUid != null) req.header("X-Library-Id", tenantUid);
        MvcResult r = mvc.perform(req).andExpect(status().is(expect)).andReturn();
        String s = r.getResponse().getContentAsString();
        return s.isBlank() ? null : TestApi.readJson(r);
    }

    private JsonNode fetch(String path, String token, String tenantUid, int expect) throws Exception {
        var req = get(path).header("Authorization", TestApi.bearer(token));
        if (tenantUid != null) req.header("X-Library-Id", tenantUid);
        MvcResult r = mvc.perform(req).andExpect(status().is(expect)).andReturn();
        String s = r.getResponse().getContentAsString();
        return s.isBlank() ? null : TestApi.readJson(r);
    }

    @BeforeAll
    void context() throws Exception {
        admin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        staff = TestApi.login(mvc, "staff@libranet.app", "Library12345!");
        memberAToken = onboardPatron("circ-a");
        memberBToken = onboardPatron("circ-b");
        dbConceptsId = bookId(staff, "Database System Concepts");
        osConceptsId = bookId(staff, "Operating System Concepts");
    }

    private String onboardPatron(String prefix) throws Exception {
        String email = prefix + "-" + System.nanoTime() + "@example.com";
        TestApi.register(mvc, email, "Password12345", "Circ " + prefix);
        String token = TestApi.login(mvc, email, "Password12345");
        JsonNode joined = call("/api/v1/libraries/pentecost/join", token, null, "{}", 200);
        String membershipId = joined.path("id").asText();
        JsonNode approved = call("/api/v1/tenant/members/" + membershipId + "/approve",
                admin, DevDataSeeder.PENTECOST_UID, "{}", 200);
        if (memberACard == null) memberACard = approved.path("membershipNumber").asText();
        else memberBCard = approved.path("membershipNumber").asText();
        if (memberAMembershipId == null) memberAMembershipId = membershipId;
        if (memberAToken == null) memberAToken = token; else memberBToken = token;
        return memberAToken == null ? token : token;
    }

    private String bookId(String staffToken, String title) throws Exception {
        JsonNode page = fetch("/api/v1/tenant/catalog/books?q=" + title.replace(" ", "_"),
                staffToken, DevDataSeeder.PENTECOST_UID, 200);
        // underscore works as wildcard-free substring? no — search by exact words instead
        if (page.path("content").isEmpty()) {
            page = fetch("/api/v1/tenant/catalog/books?q=" + title.split(" ")[0],
                    staffToken, DevDataSeeder.PENTECOST_UID, 200);
        }
        for (JsonNode b : page.path("content")) {
            if (title.startsWith(b.path("title").asText().substring(0, Math.min(8, b.path("title").asText().length())))) {
                return b.path("id").asText();
            }
        }
        return page.path("content").get(0).path("id").asText();
    }

    // ---------- policy ----------

    @Test
    @Order(1)
    void policyDefaultsEditableByAdminOnly() throws Exception {
        JsonNode policy = fetch("/api/v1/tenant/circulation/policy", staff, DevDataSeeder.PENTECOST_UID, 200);
        assertThat(policy.path("loanDays").asInt()).isEqualTo(14);
        assertThat(policy.path("finePerDayMinor").asLong()).isEqualTo(200);

        mvc.perform(put("/api/v1/tenant/circulation/policy")
                        .header("Authorization", TestApi.bearer(memberAToken))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"loanDays\":30}"))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/tenant/circulation/policy")
                        .header("Authorization", TestApi.bearer(staff)) // LIBRARIAN is staff but not admin
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"loanDays\":30}"))
                .andExpect(status().isForbidden());

        JsonNode updated = null;
        MvcResult r = mvc.perform(put("/api/v1/tenant/circulation/policy")
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"loanDays\":21}"))
                .andExpect(status().isOk()).andReturn();
        updated = TestApi.readJson(r);
        assertThat(updated.path("loanDays").asInt()).isEqualTo(21);
    }

    // ---------- issue / renew / late return / fines ----------

    @Test
    @Order(2)
    void deskIssueRenewReturnWithLateFineSettleAndWaive() throws Exception {
        // card scan lookup
        JsonNode lookup = fetch("/api/v1/tenant/circulation/members/lookup?number=" + memberACard,
                staff, DevDataSeeder.PENTECOST_UID, 200);
        assertThat(lookup.path("activeLoans").asLong()).isZero();

        // issue by scanned identifiers
        JsonNode loan = call("/api/v1/tenant/circulation/loans", staff, DevDataSeeder.PENTECOST_UID,
                "{\"membershipNumber\":\"" + memberACard + "\",\"barcode\":\"PU-000001\"}", 201);
        String loanA = loan.path("id").asText();
        assertThat(loan.path("status").asText()).isEqualTo("ACTIVE");

        // same copy cannot be issued twice
        call("/api/v1/tenant/circulation/loans", staff, DevDataSeeder.PENTECOST_UID,
                "{\"membershipNumber\":\"" + memberACard + "\",\"barcode\":\"PU-000001\"}", 409);

        // copy register reflects BORROWED
        JsonNode copiesA = fetch("/api/v1/tenant/catalog/books/" + dbConceptsId + "/copies",
                staff, DevDataSeeder.PENTECOST_UID, 200);
        for (JsonNode c : copiesA) {
            if ("PU-000001".equals(c.path("barcode").asText())) {
                assertThat(c.path("status").asText()).isEqualTo("BORROWED");
            }
        }

        // member renews own loan (maxRenewals = 2)
        JsonNode renewed = call("/api/v1/tenant/circulation/loans/" + loanA + "/renew",
                memberAToken, DevDataSeeder.PENTECOST_UID, "{}", 200);
        assertThat(renewed.path("renewedCount").asInt()).isEqualTo(1);
        call("/api/v1/tenant/circulation/loans/" + loanA + "/renew", staff, DevDataSeeder.PENTECOST_UID,
                "{}", 200);
        call("/api/v1/tenant/circulation/loans/" + loanA + "/renew", memberAToken,
                DevDataSeeder.PENTECOST_UID, "{}", 409)
                .path("code").asText().equals("RENEWAL_LIMIT_REACHED");

        // on-time return → no fine
        JsonNode returned = call("/api/v1/tenant/circulation/returns", staff, DevDataSeeder.PENTECOST_UID,
                "{\"barcode\":\"PU-000001\"}", 200);
        assertThat(returned.path("status").asText()).isEqualTo("RETURNED");
        JsonNode openFines0 = fetch("/api/v1/tenant/circulation/fines/me/open",
                memberAToken, DevDataSeeder.PENTECOST_UID, 200);
        assertThat(openFines0).isEmpty();

        // late return → automatic LATE fine (loanDays=0, graceDays=0 → due immediately)
        setPolicy(admin, "{\"loanDays\":0,\"graceDays\":0,\"finePerDayMinor\":200}");
        call("/api/v1/tenant/circulation/loans", staff, DevDataSeeder.PENTECOST_UID,
                "{\"membershipNumber\":\"" + memberACard + "\",\"barcode\":\"PU-000002\"}", 201);
        call("/api/v1/tenant/circulation/returns", staff, DevDataSeeder.PENTECOST_UID,
                "{\"barcode\":\"PU-000002\"}", 200);
        JsonNode openFines = fetch("/api/v1/tenant/circulation/fines/me/open",
                memberAToken, DevDataSeeder.PENTECOST_UID, 200);
        assertThat(openFines).hasSize(1);
        assertThat(openFines.get(0).path("type").asText()).isEqualTo("LATE");
        assertThat(openFines.get(0).path("amountMinor").asLong()).isEqualTo(200);
        String fineId = openFines.get(0).path("id").asText();

        // notification fired
        JsonNode notes = fetch("/api/v1/tenant/notifications/me",
                memberAToken, DevDataSeeder.PENTECOST_UID, 200);
        assertThat(notes.path("content").toString()).contains("FINE_ASSESSED");

        // settle with exact amount at the desk
        JsonNode settled = call("/api/v1/tenant/circulation/fines/" + fineId + "/pay",
                staff, DevDataSeeder.PENTECOST_UID,
                "{\"amountMinor\":200,\"settlement\":\"cash at desk\"}", 200);
        assertThat(settled.path("status").asText()).isEqualTo("PAID");

        // manual fine → librarian cannot waive, admin can
        JsonNode manual = call("/api/v1/tenant/circulation/fines", admin, DevDataSeeder.PENTECOST_UID,
                "{\"membershipId\":\"" + memberAMembershipId + "\",\"type\":\"MANUAL\","
                        + "\"amountMinor\":500,\"note\":\"photocopy credit misuse\"}", 201);
        String manualId = manual.path("id").asText();
        call("/api/v1/tenant/circulation/fines/" + manualId + "/waive?reason=goodwill",
                staff, DevDataSeeder.PENTECOST_UID, "{}", 403);
        JsonNode waived = call("/api/v1/tenant/circulation/fines/" + manualId + "/waive?reason=goodwill",
                admin, DevDataSeeder.PENTECOST_UID, "{}", 200);
        assertThat(waived.path("status").asText()).isEqualTo("WAIVED");

        // restore a sane default policy for later phases/tests
        setPolicy(admin, "{\"loanDays\":14,\"pickupDeadlineHours\":48}");
    }

    // ---------- reservation lifecycle ----------

    @Test
    @Order(3)
    void reservationLifecycleReadyCollectReturn() throws Exception {
        setPolicy(admin, "{\"loanDays\":14}");

        // B reserves OS Concepts (copy available) → promotion assigns copy immediately
        JsonNode res = call("/api/v1/tenant/circulation/reservations", memberBToken,
                DevDataSeeder.PENTECOST_UID, "{\"bookId\":\"" + osConceptsId + "\"}", 201);
        assertThat(res.path("status").asText()).isEqualTo("WAITING_FOR_PICKUP");
        assertThat(res.path("copyId").asText()).isNotEmpty();
        String resId = res.path("id").asText();

        // held copy shows RESERVED on the register
        JsonNode copies = fetch("/api/v1/tenant/catalog/books/" + osConceptsId + "/copies",
                staff, DevDataSeeder.PENTECOST_UID, 200);
        assertThat(copies.get(0).path("status").asText()).isEqualTo("RESERVED");

        // staff pulls it to the hold shelf
        JsonNode ready = call("/api/v1/tenant/circulation/reservations/" + resId + "/ready",
                staff, DevDataSeeder.PENTECOST_UID, "{}", 200);
        assertThat(ready.path("status").asText()).isEqualTo("READY_FOR_PICKUP");
        assertThat(fetch("/api/v1/tenant/circulation/reservations/ready",
                staff, DevDataSeeder.PENTECOST_UID, 200).toString()).contains(resId);

        // member collects → becomes a loan, copy BORROWED
        JsonNode loan = call("/api/v1/tenant/circulation/reservations/" + resId + "/collect",
                staff, DevDataSeeder.PENTECOST_UID, "{}", 200);
        assertThat(loan.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(loan.path("membershipNumber").asText()).isEqualTo(memberBCard);

        JsonNode mine = fetch("/api/v1/tenant/circulation/reservations/me",
                memberBToken, DevDataSeeder.PENTECOST_UID, 200);
        assertThat(mine.get(0).path("status").asText()).isEqualTo("COLLECTED");
        assertThat(mine.get(0).path("loanId").asText()).isEqualTo(loan.path("id").asText());

        // return closes everything
        call("/api/v1/tenant/circulation/returns", staff, DevDataSeeder.PENTECOST_UID,
                "{\"barcode\":\"PU-000003\"}", 200);
        JsonNode copies2 = fetch("/api/v1/tenant/catalog/books/" + osConceptsId + "/copies",
                staff, DevDataSeeder.PENTECOST_UID, 200);
        assertThat(copies2.get(0).path("status").asText()).isEqualTo("AVAILABLE");
    }

    @Test
    @Order(4)
    void queuePromotionRenewalBlockAndMemberCancel() throws Exception {
        setPolicy(admin, "{\"loanDays\":14}");
        // A borrows BOTH Database Systems copies
        JsonNode l1 = call("/api/v1/tenant/circulation/loans", staff, DevDataSeeder.PENTECOST_UID,
                "{\"membershipNumber\":\"" + memberACard + "\",\"barcode\":\"PU-000001\"}", 201);
        call("/api/v1/tenant/circulation/loans", staff, DevDataSeeder.PENTECOST_UID,
                "{\"membershipNumber\":\"" + memberACard + "\",\"barcode\":\"PU-000002\"}", 201);

        // B reserves → stays queued (no free copy)
        JsonNode res = call("/api/v1/tenant/circulation/reservations", memberBToken,
                DevDataSeeder.PENTECOST_UID, "{\"bookId\":\"" + dbConceptsId + "\"}", 201);
        assertThat(res.path("status").asText()).isEqualTo("RESERVED");
        assertThat(res.path("queuePosition").asLong()).isEqualTo(1);
        String resId = res.path("id").asText();

        // renewal is blocked while someone waits
        call("/api/v1/tenant/circulation/loans/" + l1.path("id").asText() + "/renew",
                staff, DevDataSeeder.PENTECOST_UID, "{}", 409);

        // duplicate reservation by same member → 409
        call("/api/v1/tenant/circulation/reservations", memberBToken,
                DevDataSeeder.PENTECOST_UID, "{\"bookId\":\"" + dbConceptsId + "\"}", 409);

        // return of one copy promotes the queue head
        call("/api/v1/tenant/circulation/returns", staff, DevDataSeeder.PENTECOST_UID,
                "{\"barcode\":\"PU-000001\"}", 200);
        JsonNode mine = fetch("/api/v1/tenant/circulation/reservations/me",
                memberBToken, DevDataSeeder.PENTECOST_UID, 200);
        JsonNode top = mine.get(0);
        assertThat(top.path("id").asText()).isEqualTo(resId);
        assertThat(top.path("status").asText()).isEqualTo("WAITING_FOR_PICKUP");

        // member cancels → held copy released
        JsonNode cancelled = call("/api/v1/tenant/circulation/reservations/" + resId + "/cancel",
                memberBToken, DevDataSeeder.PENTECOST_UID, "{}", 200);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        JsonNode copies = fetch("/api/v1/tenant/catalog/books/" + dbConceptsId + "/copies",
                staff, DevDataSeeder.PENTECOST_UID, 200);
        for (JsonNode c : copies) {
            if ("PU-000001".equals(c.path("barcode").asText())) {
                assertThat(c.path("status").asText()).isEqualTo("AVAILABLE");
            }
        }

        // tidy up: return A's second copy
        call("/api/v1/tenant/circulation/returns", staff, DevDataSeeder.PENTECOST_UID,
                "{\"barcode\":\"PU-000002\"}", 200);
    }

    @Test
    @Order(5)
    void pickupDeadlineMissedExpiresReservation() throws Exception {
        setPolicy(admin, "{\"pickupDeadlineHours\":0}"); // deadline = immediately (test knob)
        JsonNode res = call("/api/v1/tenant/circulation/reservations", memberBToken,
                DevDataSeeder.PENTECOST_UID, "{\"bookId\":\"" + osConceptsId + "\"}", 201);
        assertThat(res.path("status").asText()).isEqualTo("WAITING_FOR_PICKUP");
        String resId = res.path("id").asText();

        // any desk action runs the sweep: this reservation is now expired
        call("/api/v1/tenant/circulation/reservations/" + resId + "/ready",
                staff, DevDataSeeder.PENTECOST_UID, "{}", 409)
                .path("code").asText().equals("NOT_WAITING");
        JsonNode mine = fetch("/api/v1/tenant/circulation/reservations/me",
                memberBToken, DevDataSeeder.PENTECOST_UID, 200);
        assertThat(mine.toString()).contains("EXPIRED");

        // held copy was released
        JsonNode copies = fetch("/api/v1/tenant/catalog/books/" + osConceptsId + "/copies",
                staff, DevDataSeeder.PENTECOST_UID, 200);
        assertThat(copies.get(0).path("status").asText()).isEqualTo("AVAILABLE");
        setPolicy(admin, "{\"pickupDeadlineHours\":48}");
    }

    // ---------- plan gating + seeded data ----------

    @Test
    @Order(6)
    void accraStarterPlanHasNoReservationsFeature() throws Exception {
        String patron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        JsonNode page = fetch("/api/v1/tenant/catalog/books?q=Things", patron, DevDataSeeder.ACCRA_UID, 200);
        String accraBook = page.path("content").get(0).path("id").asText();
        JsonNode err = call("/api/v1/tenant/circulation/reservations", patron,
                DevDataSeeder.ACCRA_UID, "{\"bookId\":\"" + accraBook + "\"}", 403);
        assertThat(err.path("code").asText()).isEqualTo("FEATURE_NOT_IN_PLAN");
    }

    @Test
    @Order(7)
    void seededAccraLoanAndNotificationFeed() throws Exception {
        String patron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        JsonNode loans = fetch("/api/v1/tenant/circulation/loans/me", patron, DevDataSeeder.ACCRA_UID, 200);
        assertThat(loans).hasSize(1);
        assertThat(loans.get(0).path("bookTitle").asText()).isEqualTo("Homegoing");
        assertThat(loans.get(0).path("overdue").asBoolean()).isFalse();

        JsonNode unread = fetch("/api/v1/tenant/notifications/me/unread-count",
                patron, DevDataSeeder.ACCRA_UID, 200);
        assertThat(unread.path("unread").asLong()).isGreaterThanOrEqualTo(1);
        call("/api/v1/tenant/notifications/me/read-all", patron, DevDataSeeder.ACCRA_UID, "{}", 200);
        assertThat(fetch("/api/v1/tenant/notifications/me/unread-count",
                patron, DevDataSeeder.ACCRA_UID, 200).path("unread").asLong()).isZero();
    }

    @Test
    @Order(8)
    void patronsCannotOperateTheDesk() throws Exception {
        JsonNode err = null;
        err = call("/api/v1/tenant/circulation/loans", memberAToken, DevDataSeeder.PENTECOST_UID,
                "{\"membershipNumber\":\"" + memberACard + "\",\"barcode\":\"PU-000003\"}", 403);
        assertThat(err.path("code").asText()).isEqualTo("INSUFFICIENT_LIBRARY_ROLE");
        // card lookup is staff data too
        fetch("/api/v1/tenant/circulation/members/lookup?number=" + memberACard,
                memberAToken, DevDataSeeder.PENTECOST_UID, 403);
    }

    private void setPolicy(String adminToken, String json) throws Exception {
        mvc.perform(put("/api/v1/tenant/circulation/policy")
                        .header("Authorization", TestApi.bearer(adminToken))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }
}
