package com.libranet.issues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Audit-P2 support lane (spec §17–§23), in the seeded **Auntie Esi Writes** tenant
 * (LIB-AW2026). Author = the tenant's seeded LIBRARY_ADMIN; readers join via open join
 * (auto-approved, per the PUBLIC library policy). No other suite reads issue rows, and the
 * notification assertions elsewhere only check for `contains(type)` — new ISSUE_* types
 * cannot disturb them.
 */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IssuesFlowTest {

    @Autowired MockMvc mvc;

    String staff;    // Esi Marfo — seeded LIBRARY_ADMIN of Auntie Esi Writes
    String reader;   // freshly registered reader, auto-approved member
    String reader2;  // second reader — must never see reader 1's issues
    String firstIssueId;
    String firstRef;

    private MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", TestApi.bearer(token))
                .header("X-Library-Id", DevDataSeeder.AUTHOR_UID)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private JsonNode createIssue(String token, String subject, String key, int expect) throws Exception {
        MvcResult r = mvc.perform(api(post("/api/v1/tenant/issues"), token)
                        .content("{\"category\":\"LOANS\",\"subject\":\"" + subject + "\","
                                + "\"description\":\"Details for: " + subject + "\","
                                + "\"clientKey\":\"" + key + "\"}"))
                .andExpect(status().is(expect)).andReturn();
        return TestApi.readJson(r);
    }

    @BeforeAll
    void setup() throws Exception {
        staff = TestApi.login(mvc, "author@libranet.app", "Author12345!");
        long stamp = System.nanoTime();
        for (int i = 1; i <= 2; i++) {
            String email = "issue-reader" + i + "-" + stamp + "@libranet.dev";
            TestApi.register(mvc, email, "ReaderPass12345", "Issue Reader " + i);
            String token = TestApi.login(mvc, email, "ReaderPass12345");
            mvc.perform(post("/api/v1/libraries/auntie-esi-writes/join")
                            .header("Authorization", TestApi.bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());
            if (i == 1) reader = token; else reader2 = token;
        }
    }

    // ---------- reporter side ----------

    @Test
    @Order(1)
    void patronCreatesStructuredIssue() throws Exception {
        JsonNode created = createIssue(reader, "Cannot renew The Market of Whispers online", "k-main", 201);
        firstIssueId = created.path("id").asText();
        firstRef = created.path("ref").asText();
        assertThat(firstRef).matches("LN-\\d{6}");
        assertThat(created.path("status").asText()).isEqualTo("OPEN");
        assertThat(created.path("category").asText()).isEqualTo("LOANS");
        assertThat(created.path("source").asText()).isEqualTo("APP");
        assertThat(created.path("reporterName").asText()).isEqualTo("Issue Reader 1");
    }

    @Test
    @Order(2)
    void createIsIdempotentPerClientKey() throws Exception {
        JsonNode replay = createIssue(reader, "Cannot renew The Market of Whispers online", "k-main", 201);
        assertThat(replay.path("id").asText()).isEqualTo(firstIssueId);
        JsonNode mine = TestApi.readJson(mvc.perform(api(get("/api/v1/tenant/issues/me"), reader))
                .andExpect(status().isOk()).andReturn());
        assertThat(mine.path("page").path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    @Order(3)
    void inputIsValidated() throws Exception {
        mvc.perform(api(post("/api/v1/tenant/issues"), reader)
                        .content("{\"category\":\"WIZARDRY\",\"subject\":\"x\",\"description\":\"long enough body\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ISSUE_CATEGORY_INVALID"));
        String huge = "x".repeat(10001);
        mvc.perform(api(post("/api/v1/tenant/issues"), reader)
                        .content("{\"category\":\"TECHNICAL\",\"subject\":\"big\",\"description\":\"" + huge + "\"}"))
                .andExpect(status().isBadRequest()); // @Size 10000 — spec §21
    }

    @Test
    @Order(4)
    void dailyLimitIsTenPerUser() throws Exception {
        for (int i = 1; i <= 9; i++) {
            createIssue(reader, "Extra issue " + i, "k-extra-" + i, 201);
        }
        mvc.perform(api(post("/api/v1/tenant/issues"), reader)
                        .content("{\"category\":\"OTHER\",\"subject\":\"eleventh today\","
                                + "\"description\":\"one too many\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ISSUE_RATE_LIMIT"));
        // …but adding to an existing thread is never rate-limited
        mvc.perform(api(post("/api/v1/tenant/issues/" + firstIssueId + "/messages"), reader)
                        .content("{\"body\":\"Follow-up detail: renew button greys out after tap.\"}"))
                .andExpect(status().isCreated());
    }

    // ---------- staff side ----------

    @Test
    @Order(5)
    void inboxIsStaffOnly() throws Exception {
        mvc.perform(api(get("/api/v1/tenant/issues/inbox"), reader))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_LIBRARY_ROLE"));
        JsonNode inbox = TestApi.readJson(mvc.perform(api(get("/api/v1/tenant/issues/inbox?status=OPEN"), staff))
                .andExpect(status().isOk()).andReturn());
        assertThat(inbox.path("page").path("totalElements").asInt()).isGreaterThanOrEqualTo(10);
    }

    @Test
    @Order(6)
    void requestInfoFlipsToNeedsInfoAndNotifiesReporter() throws Exception {
        mvc.perform(api(post("/api/v1/tenant/issues/" + firstIssueId + "/request-info"), staff)
                        .content("{\"note\":\"Which error message do you see when you tap renew?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_INFO"));
        JsonNode feed = TestApi.readJson(mvc.perform(
                        api(get("/api/v1/tenant/notifications/me"), reader))
                .andExpect(status().isOk()).andReturn());
        assertThat(feed.path("content").toString()).contains("ISSUE_NEEDS_INFO");
    }

    @Test
    @Order(7)
    void reporterReplyHandsIssueBackToTheTeam() throws Exception {
        mvc.perform(api(post("/api/v1/tenant/issues/" + firstIssueId + "/messages"), reader)
                        .content("{\"body\":\"It says 'renewal limit reached for this copy'.\"}"))
                .andExpect(status().isCreated());
        mvc.perform(api(get("/api/v1/tenant/issues/" + firstIssueId), staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue.status").value("IN_PROGRESS"));
    }

    @Test
    @Order(8)
    void internalNotesNeverReachTheReporter() throws Exception {
        mvc.perform(api(post("/api/v1/tenant/issues/" + firstIssueId + "/messages"), staff)
                        .content("{\"body\":\"STAFF SECRET: policy edited by mistake last night.\","
                                + "\"internal\":true}"))
                .andExpect(status().isCreated());
        JsonNode asPatron = TestApi.readJson(mvc.perform(
                        api(get("/api/v1/tenant/issues/" + firstIssueId), reader))
                .andExpect(status().isOk()).andReturn());
        assertThat(asPatron.path("messages").toString()).doesNotContain("STAFF SECRET");
        for (JsonNode m : asPatron.path("messages")) {
            assertThat(m.path("internal").asBoolean()).isFalse();
        }
        JsonNode asStaff = TestApi.readJson(mvc.perform(
                        api(get("/api/v1/tenant/issues/" + firstIssueId), staff))
                .andExpect(status().isOk()).andReturn());
        assertThat(asStaff.path("messages").toString()).contains("STAFF SECRET");
        // and a patron cannot forge one
        mvc.perform(api(post("/api/v1/tenant/issues/" + firstIssueId + "/messages"), reader)
                        .content("{\"body\":\"sneaky\",\"internal\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTERNAL_NOTE_FORBIDDEN"));
    }

    @Test
    @Order(9)
    void assignAndPriorityAreStaffTools() throws Exception {
        mvc.perform(api(post("/api/v1/tenant/issues/" + firstIssueId + "/assign"), staff)
                        .content("{\"assigneeMembershipId\":\"me\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeName").value("Esi Marfo"));
        mvc.perform(api(post("/api/v1/tenant/issues/" + firstIssueId + "/priority"), staff)
                        .content("{\"priority\":\"URGENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("URGENT"));
        mvc.perform(api(post("/api/v1/tenant/issues/" + firstIssueId + "/assign"), reader)
                        .content("{\"assigneeMembershipId\":\"me\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(api(post("/api/v1/tenant/issues/" + firstIssueId + "/priority"), reader)
                        .content("{\"priority\":\"LOW\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(10)
    void resolveThenReporterReplyReopens() throws Exception {
        mvc.perform(api(post("/api/v1/tenant/issues/" + firstIssueId + "/resolve"), staff)
                        .content("{\"note\":\"We reset the renewal counter on your copy — try again.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
        JsonNode feed = TestApi.readJson(mvc.perform(
                        api(get("/api/v1/tenant/notifications/me"), reader))
                .andExpect(status().isOk()).andReturn());
        assertThat(feed.path("content").toString()).contains("ISSUE_RESOLVED");

        // reporter says it's still broken → automatically back to OPEN
        mvc.perform(api(post("/api/v1/tenant/issues/" + firstIssueId + "/messages"), reader)
                        .content("{\"body\":\"Still failing, same message.\"}"))
                .andExpect(status().isCreated());
        mvc.perform(api(get("/api/v1/tenant/issues/" + firstIssueId), staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue.status").value("OPEN"));

        // resolved issues refuse status jumps without a reopen
        mvc.perform(api(post("/api/v1/tenant/issues/" + firstIssueId + "/resolve"), staff)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        mvc.perform(api(post("/api/v1/tenant/issues/" + firstIssueId + "/request-info"), staff)
                        .content("{\"note\":\"late question\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ISSUE_CLOSED"));
        mvc.perform(api(post("/api/v1/tenant/issues/" + firstIssueId + "/reopen"), staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    // ---------- privacy / isolation ----------

    @Test
    @Order(11)
    void readerCannotSeeAnotherMembersIssue() throws Exception {
        mvc.perform(api(get("/api/v1/tenant/issues/" + firstIssueId), reader2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ISSUE_NOT_FOUND"));
        // not even a message on it
        mvc.perform(api(post("/api/v1/tenant/issues/" + firstIssueId + "/messages"), reader2)
                        .content("{\"body\":\"metoo\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(12)
    void issueCannotEscapeAcrossTenants() throws Exception {
        String accraPatron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        mvc.perform(get("/api/v1/tenant/issues/" + firstIssueId)
                        .header("Authorization", TestApi.bearer(accraPatron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isNotFound());
        // and the Accra inbox/mine views carry no Auntie rows
        JsonNode mine = TestApi.readJson(mvc.perform(get("/api/v1/tenant/issues/me")
                        .header("Authorization", TestApi.bearer(accraPatron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk()).andReturn());
        assertThat(mine.path("content").toString()).doesNotContain(firstRef);
        // a staff token from a DIFFERENT tenant is just another non-member here
        String pentecostAdmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        mvc.perform(get("/api/v1/tenant/issues/inbox")
                        .header("Authorization", TestApi.bearer(pentecostAdmin))
                        .header("X-Library-Id", DevDataSeeder.AUTHOR_UID))
                .andExpect(status().isForbidden());
    }
}
