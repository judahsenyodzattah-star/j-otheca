package com.jotheca.circulation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jotheca.seed.DevDataSeeder;
import com.jotheca.support.TestApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;

/**
 * Regression: lazy overdue-reminder sweep (audit 2026-10-10). An ACTIVE loan past due triggers exactly one
 * in-app LOAN_OVERDUE notification on the member's own loan read, and a second read does NOT duplicate it
 * (idempotent via loans.reminder_sent_at). Self-contained (own book/copy/patron) so it does not collide with
 * other suites' rows.
 *
 * Run with: mvn -Dtest=OverdueReminderTest test
 */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class OverdueReminderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private String membershipId;
    private String loanId;
    private String copyId;
    private String bookId;

    @AfterEach
    void cleanUp() {
        if (membershipId != null) {
            jdbc.update("delete from notifications where user_id = "
                    + "(select user_id from memberships where id=?)", membershipId);
        }
        if (loanId != null) jdbc.update("delete from loans where id=?", loanId);
        if (copyId != null) jdbc.update("delete from book_copies where id=?", copyId);
        if (bookId != null) jdbc.update("delete from books where id=?", bookId);
    }

    private JsonNode call(String path, String token, String tenantUid, String body, int expect) throws Exception {
        MockHttpServletRequestBuilder req = body == null
                ? get(path)
                : post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req.header("Authorization", TestApi.bearer(token));
        if (tenantUid != null) req.header("X-Library-Id", tenantUid);
        MvcResult r = mvc.perform(req).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(expect)).andReturn();
        return MAPPER.readTree(r.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Overdue loan → exactly one reminder, then idempotent on re-read")
    void overdueReminderOnce() throws Exception {
        String admin = TestApi.login(mvc, "libadmin@jotheca.app", "Library12345!");

        // fresh approved patron in Pentecost
        String email = "remind-" + System.nanoTime() + "@example.com";
        TestApi.register(mvc, email, "Password12345", "Remind Patron");
        String patron = TestApi.login(mvc, email, "Password12345");
        JsonNode joined = call("/api/v1/libraries/pentecost/join", patron, null, "{}", 200);
        membershipId = joined.path("id").asText();
        JsonNode approved = call("/api/v1/tenant/members/" + membershipId + "/approve",
                admin, DevDataSeeder.PENTECOST_UID, "{}", 200);
        String card = approved.path("membershipNumber").asText();

        // fresh book + copy with a unique barcode → no collision with other suites' copies
        String suffix = "-" + System.nanoTime();
        JsonNode book = call("/api/v1/tenant/catalog/books", admin, DevDataSeeder.PENTECOST_UID,
                "{\"title\":\"Overdue Guide" + suffix + "\",\"author\":\"Audit\"}", 201);
        bookId = book.path("id").asText();
        JsonNode copy = call("/api/v1/tenant/catalog/books/" + bookId + "/copies", admin,
                DevDataSeeder.PENTECOST_UID, "{\"barcode\":\"RM-OVERDUE" + suffix + "\"}", 201);
        copyId = copy.path("id").asText();

        // issue the copy to the patron
        JsonNode loan = call("/api/v1/tenant/circulation/loans", admin, DevDataSeeder.PENTECOST_UID,
                "{\"membershipNumber\":\"" + card + "\",\"copyId\":\"" + copyId + "\"}", 201);
        loanId = loan.path("id").asText();

        // backdate due_at into the past so the loan is overdue (sweep keys off due_at < now)
        jdbc.update("update loans set due_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(30))), loanId);

        // the member's own loan read runs the lazy sweep
        call("/api/v1/tenant/circulation/loans/me", patron, DevDataSeeder.PENTECOST_UID, null, 200);

        long reminders = countOverdueReminders(membershipId);
        assertThat(reminders).as("one LOAN_OVERDUE notification after first read").isEqualTo(1);
        assertThat(reminderStamped(loanId)).as("reminder_sent_at stamped on the loan").isEqualTo(1);

        // a second read must NOT create a duplicate reminder (idempotent)
        call("/api/v1/tenant/circulation/loans/me", patron, DevDataSeeder.PENTECOST_UID, null, 200);
        assertThat(countOverdueReminders(membershipId)).as("still exactly one reminder after re-read").isEqualTo(1);
    }

    private long countOverdueReminders(String membershipId) {
        return jdbc.queryForObject(
                "select count(*) from notifications where type='LOAN_OVERDUE' and user_id = "
                        + "(select user_id from memberships where id=?)",
                Long.class, membershipId);
    }

    private long reminderStamped(String loanId) {
        return jdbc.queryForObject(
                "select count(*) from loans where id=? and reminder_sent_at is not null",
                Long.class, loanId);
    }
}
