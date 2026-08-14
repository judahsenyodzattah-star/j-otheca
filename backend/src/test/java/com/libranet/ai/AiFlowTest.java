package com.libranet.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.libranet.ai.embed.LocalHashEmbedder;
import com.libranet.ai.service.QueryUnderstanding;
import com.libranet.audit.AuditQueryService;
import com.libranet.seed.DevDataSeeder;
import com.libranet.support.TestApi;
import com.libranet.tenancy.repo.LibraryRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase-10 AI flow, entirely in the seeded **Ashesi** tenant (LIB-AU2026, ENTERPRISE plan,
 * invite-code auto-approve join): no other suite counts anything there. Embeddings are
 * deterministic (local hash embedder), so ranking assertions are exact.
 */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiFlowTest {

    @Autowired MockMvc mvc;
    @Autowired LibraryRepository libraries;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditQueryService auditQuery;

    String admin;      // fresh LIBRARY_ADMIN of Ashesi (seeds hold no staff membership)
    String reader;     // auto-approved STUDENT member via invite code
    String tenantId;
    String tenantUid;

    String javaId, cookingId, databaseId, mlId, pythonId;

    private MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", TestApi.bearer(token))
                .header("X-Library-Id", tenantUid)
                .contentType(MediaType.APPLICATION_JSON);
    }

    @BeforeAll
    void setup() throws Exception {
        tenantUid = DevDataSeeder.ASHESI_UID;
        tenantId = libraries.findByUid(tenantUid).orElseThrow().getId();

        // staff for the AI sandbox: freshly registered user joins via invite code and is
        // promoted to LIBRARY_ADMIN directly in the membership table (fixtures only).
        long stamp = System.nanoTime();
        String adminEmail = "ai-admin-" + stamp + "@libranet.dev";
        TestApi.register(mvc, adminEmail, "AiAdmin12345", "AI Admin");
        admin = TestApi.login(mvc, adminEmail, "AiAdmin12345");
        mvc.perform(post("/api/v1/libraries/ashesi/join")
                        .header("Authorization", TestApi.bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + DevDataSeeder.ASHESI_INVITE_CODE + "\"}"))
                .andExpect(status().isOk());
        jdbc.update("UPDATE memberships SET role = 'LIBRARY_ADMIN' WHERE tenant_id = ?"
                + " AND user_id = (SELECT id FROM users WHERE email = ?)", tenantId, adminEmail);

        String readerEmail = "ai-reader-" + stamp + "@libranet.dev";
        TestApi.register(mvc, readerEmail, "AiReader12345", "AI Reader");
        reader = TestApi.login(mvc, readerEmail, "AiReader12345");
        mvc.perform(post("/api/v1/libraries/ashesi/join")
                        .header("Authorization", TestApi.bearer(reader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + DevDataSeeder.ASHESI_INVITE_CODE + "\"}"))
                .andExpect(status().isOk());
    }

    @AfterAll
    void restoreSeededData() {
        // anything this suite INSERTed into shared PLAN CATALOG rows is reverted even on failure
        jdbc.update("UPDATE subscription_plans SET limits = "
                + "'{\"storageGb\":500,\"maxMembers\":50000,\"maxBooks\":250000,\"maxStaff\":100,"
                + "\"aiCallsMonthly\":100000,\"maxBranches\":25,\"reservations\":true,"
                + "\"notifications\":[\"in_app\",\"email\",\"push\"],\"analytics\":\"advanced\","
                + "\"barcodeQr\":true,\"apiAccess\":true,\"sso\":true,\"prioritySupport\":true}' "
                + "WHERE code = 'ENTERPRISE'");
    }

    private String createBook(String title, String author, String year, String subjects,
                              String description) throws Exception {
        MvcResult r = mvc.perform(api(post("/api/v1/tenant/catalog/books"), admin)
                        .content("{\"title\":\"" + title + "\",\"author\":\"" + author + "\","
                                + "\"publishedDate\":\"" + year + "\",\"language\":\"English\","
                                + "\"subjects\":\"" + subjects + "\",\"description\":\""
                                + description + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return TestApi.readJson(r).path("id").asText();
    }

    // ---------- 1-2: embedder + query understanding (pure units, no HTTP) ----------

    @Test @Order(1)
    void localEmbedderIsDeterministicNormalizedAndSemantic() {
        LocalHashEmbedder e = new LocalHashEmbedder();
        float[] a = e.embed("Beginner Java programming, 2nd edition");
        float[] b = e.embed("beginner java programming second edition");
        assertThat(a.length).isEqualTo(256);
        // L2-normalized
        double norm = 0;
        for (float f : a) norm += f * f;
        assertThat(norm).isBetween(0.99, 1.01);
        // near-identical texts score high; unrelated text scores low
        assertThat(com.libranet.ai.service.EmbeddingService.cosine(a, b)).isGreaterThan(0.5);
        float[] c = e.embed("medieval castle architecture");
        assertThat(com.libranet.ai.service.EmbeddingService.cosine(a, c)).isLessThan(0.35);
    }

    @Test @Order(2)
    void queryUnderstandingExtractsYearFilters() {
        var p = QueryUnderstanding.parse("beginner Java books after 2023");
        assertThat(p.text()).isEqualTo("beginner Java books");
        assertThat(p.publishedAfter()).isEqualTo(2023);
        assertThat(p.publishedBefore()).isNull();
        var q = QueryUnderstanding.parse("older than 1990 atlases");
        assertThat(q.publishedBefore()).isEqualTo(1990);
        assertThat(q.text()).isEqualTo("atlases");
        var r = QueryUnderstanding.parse("novels in 2019");
        assertThat(r.publishedIn()).isEqualTo(2019);
        assertThat(QueryUnderstanding.matchesYear("2024", 2023, null, null)).isTrue();
        assertThat(QueryUnderstanding.matchesYear("2023", 2023, null, null)).isFalse(); // strictly after
        assertThat(QueryUnderstanding.matchesYear("2022", 2023, null, null)).isFalse();
        assertThat(QueryUnderstanding.matchesYear(null, 2023, null, null)).isFalse();
    }

    // ---------- 3-4: catalog fixtures + semantic search ----------

    @Test @Order(3)
    void staffSeedsProgrammingCatalog() throws Exception {
        javaId = createBook("Java for Beginners", "Adu Mensah", "2024",
                "Programming, Java, Beginners",
                "A friendly step-by-step introduction to Java programming for absolute beginners.");
        cookingId = createBook("Advanced French Pastry", "Kofi Owusu", "2020",
                "Cooking, Pastry",
                "Mastering lamination, croissants and pâte à choux for experienced chefs.");
        databaseId = createBook("Database Systems Explained", "Ama Serwaa", "2025",
                "Databases, SQL, Computer Science",
                "Relational theory, indexing and transactions with practical SQL labs.");
        mlId = createBook("Machine Learning Basics", "Yaw Boateng", "2024",
                "Machine Learning, Artificial Intelligence, Python",
                "Foundations of ML algorithms with hands-on Python notebooks for beginners.");
        pythonId = createBook("Python for Data Science", "Esi Adjei", "2024",
                "Python, Programming, Data Science, Beginners",
                "Learn Python programming for data analysis and machine learning, "
                + "a practical guide for beginners.");
        assertThat(javaId).isNotBlank();
    }

    @Test @Order(4)
    void semanticSearchRanksAndFiltersWithExplanations() throws Exception {
        MvcResult r = mvc.perform(api(get(
                        "/api/v1/tenant/ai/search?q=beginner Java books after 2023"), reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filters.publishedAfter").value(2023))
                .andReturn();
        JsonNode body = TestApi.readJson(r);
        assertThat(body.path("hits").size()).isGreaterThanOrEqualTo(1);
        JsonNode top = body.path("hits").get(0);
        assertThat(top.path("title").asText()).isEqualTo("Java for Beginners");
        assertThat(top.path("matchedTokens").toString()).contains("java");
        assertThat(top.path("why").toString()).contains("semantic");
        assertThat(top.path("publishedYear").asInt()).isEqualTo(2024);
        // the 2020 pastry book can NEVER appear in an after-2023 search, whatever the vector says
        for (JsonNode hit : body.path("hits")) {
            assertThat(hit.path("publishedYear").asInt()).isGreaterThan(2023);
            assertThat(hit.path("bookId").asText()).isNotEqualTo(cookingId);
        }
        // metering: usage reported on the response
        assertThat(body.path("usage").path("used").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(body.path("usage").path("limit").asLong()).isEqualTo(100000);
    }

    // ---------- 5: recommendations ----------

    @Test @Order(5)
    void recommendationsUseTheMembersOwnSignals() throws Exception {
        // reader loves Java for Beginners…
        mvc.perform(api(put("/api/v1/tenant/catalog/books/{id}/rating", javaId), reader)
                        .content("{\"stars\":5}"))
                .andExpect(status().isOk());
        // …and flagged ML Basics
        mvc.perform(api(put("/api/v1/tenant/catalog/books/{id}/interest", mlId), reader)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        MvcResult r = mvc.perform(api(get("/api/v1/tenant/ai/recommendations"), reader))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = TestApi.readJson(r);
        assertThat(body.path("basis").asText()).contains("loans");
        JsonNode recs = body.path("recommendations");
        assertThat(recs.size()).isGreaterThanOrEqualTo(1);
        // consumed titles are never echoed back
        for (JsonNode rec : recs) {
            assertThat(rec.path("bookId").asText()).isNotIn(javaId, mlId);
            assertThat(rec.path("because").isArray()).isTrue();
        }
        // the in-domain title (python/programming/beginners) is the natural recommendation
        assertThat(recs.toString()).contains("Python for Data Science");
    }

    // ---------- 6-7: guarded assistant ----------

    @Test @Order(6)
    void patronAssistantAnswersMembershipScopedIntents() throws Exception {
        // my-loans intent — scoped to the reader's own membership; they hold no active loan
        mvc.perform(api(post("/api/v1/tenant/ai/assistant/ask"), reader)
                        .content("{\"question\":\"when are my books due?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("MY_LOANS"))
                .andExpect(jsonPath("$.answer")
                        .value(org.hamcrest.Matchers.containsString("nothing on loan")));

        // policy intent — grounded in the library's real loan-policy row
        mvc.perform(api(post("/api/v1/tenant/ai/assistant/ask"), reader)
                        .content("{\"question\":\"how long can I keep a book and is there a fine?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("POLICY"));

        // staff-metric wording from a patron: polite redirect, never the numbers (spec §15)
        MvcResult r = mvc.perform(api(post("/api/v1/tenant/ai/assistant/ask"), reader)
                        .content("{\"question\":\"what should we buy more copies of?\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = TestApi.readJson(r);
        assertThat(body.path("intent").asText()).isEqualTo("STAFF_VIEW");
        assertThat(body.path("answer").asText()).doesNotContain("Machine Learning Basics");

        // patrons also get the grounded search fallback with citations
        mvc.perform(api(post("/api/v1/tenant/ai/assistant/ask"), reader)
                        .content("{\"question\":\"do we have anything about databases?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("SEARCH"))
                .andExpect(jsonPath("$.citations[0].title").value("Database Systems Explained"));
    }

    @Test @Order(7)
    void assistantAnswersGroundedAndAuditsWithoutQuestionText() throws Exception {
        // staff asks for acquisition advice → DEMAND intent (interest from step 5 is visible)
        MvcResult r = mvc.perform(api(post("/api/v1/tenant/ai/assistant/ask"), admin)
                        .content("{\"question\":\"what should we buy more copies of?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("DEMAND"))
                .andReturn();
        JsonNode body = TestApi.readJson(r);
        assertThat(body.path("answer").asText()).contains("Machine Learning Basics");
        assertThat(body.path("citations").size()).isGreaterThanOrEqualTo(1);

        // free-form question → SEARCH intent with catalog citations
        mvc.perform(api(post("/api/v1/tenant/ai/assistant/ask"), admin)
                        .content("{\"question\":\"do we have anything about databases?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("SEARCH"))
                .andExpect(jsonPath("$.citations[0].title").value("Database Systems Explained"));

        // validation + audit (metadata exists, question text never stored)
        mvc.perform(api(post("/api/v1/tenant/ai/assistant/ask"), admin).content("{\"question\":\"hi\"}"))
                .andExpect(status().isBadRequest());
        var audits = auditQuery.searchPlatform(tenantId, "AI_ASSISTANT_ASKED", null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(audits.getTotalElements()).isGreaterThanOrEqualTo(2);
        audits.getContent().forEach(entry -> assertThat(entry.metadata()).doesNotContain("databases"));
    }

    // ---------- 8: demand insights ----------

    @Test @Order(8)
    void demandInsightsAggregateSignalsAndZeroHitSearches() throws Exception {
        // member searches for something the library doesn't carry — telemetry captures it
        mvc.perform(api(get("/api/v1/tenant/catalog/books?q=xylophone repair"), reader))
                .andExpect(status().isOk());
        mvc.perform(api(get("/api/v1/tenant/catalog/books?q=xylophone repair"), reader))
                .andExpect(status().isOk());

        MvcResult r = mvc.perform(api(get("/api/v1/tenant/ai/demand-insights"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.byFeature.DEMAND_INSIGHTS").isNumber())
                .andReturn();
        JsonNode body = TestApi.readJson(r);
        // ML Basics has 1 interest (step 5) → appears as demanded with per-book numbers
        assertThat(body.path("topDemanded").toString()).contains("Machine Learning Basics");
        // doubled zero-hit search surfaces as uncatalogued demand with a suggestion
        JsonNode zeros = body.path("uncataloguedDemand");
        boolean found = false;
        for (JsonNode z : zeros) {
            if (z.path("query").asText().equals("xylophone repair")) {
                found = true;
                assertThat(z.path("occurrences").asLong()).isGreaterThanOrEqualTo(2);
                assertThat(z.path("suggestion").asText()).contains("acquiring");
            }
        }
        assertThat(found).as("zero-hit query recorded").isTrue();
    }

    // ---------- 9: embeddings admin + usage ----------

    @Test @Order(9)
    void embeddingsAreTenantPinnedAndRebuildable() throws Exception {
        Long mine = jdbc.queryForObject(
                "SELECT COUNT(*) FROM book_embeddings WHERE tenant_id = ?", Long.class, tenantId);
        Long all = jdbc.queryForObject("SELECT COUNT(*) FROM book_embeddings", Long.class);
        assertThat(mine).isGreaterThanOrEqualTo(4);                    // 4 books created in step 3
        assertThat(mine).isLessThanOrEqualTo(all);                     // others exist, never mixed

        mvc.perform(api(post("/api/v1/tenant/ai/embeddings/rebuild"), reader))
                .andExpect(status().isForbidden()); // librarian/admin only
        mvc.perform(api(post("/api/v1/tenant/ai/embeddings/rebuild"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("local-hash-256"))
                .andExpect(jsonPath("$.embeddings").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(4)));
        Long after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM book_embeddings WHERE tenant_id = ?", Long.class, tenantId);
        assertThat(after).isEqualTo(mine); // rebuild is a full replace, not a leak
    }

    // ---------- 10: plan-quota proof (data-driven) ----------

    @Test @Order(10)
    void exceedingAiCallsMonthlyAnswers403AsData() throws Exception {
        long used = jdbc.queryForObject(
                "SELECT COALESCE(SUM(calls),0) FROM ai_usage_counters"
                        + " WHERE tenant_id = ? AND feature = 'SEMANTIC_SEARCH'",
                Long.class, tenantId);
        // drop the plan's ceiling below current usage — limits are JSON data, no code touched
        jdbc.update("UPDATE subscription_plans SET limits = "
                + "'{\"storageGb\":500,\"maxMembers\":50000,\"maxBooks\":250000,\"maxStaff\":100,"
                + "\"aiCallsMonthly\":" + used + ",\"maxBranches\":25,\"reservations\":true,"
                + "\"notifications\":[\"in_app\",\"email\",\"push\"],\"analytics\":\"advanced\","
                + "\"barcodeQr\":true,\"apiAccess\":true,\"sso\":true,\"prioritySupport\":true}' "
                + "WHERE code = 'ENTERPRISE'");
        try {
            mvc.perform(api(get("/api/v1/tenant/ai/search?q=java"), admin))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PLAN_LIMIT_EXCEEDED"));
        } finally {
            restoreSeededData();
        }
        // back to normal immediately — data, not code
        mvc.perform(api(get("/api/v1/tenant/ai/search?q=java"), admin))
                .andExpect(status().isOk());
    }

    @Test @Order(11)
    void issueDraftNeverCreatesUntilTheReporterConfirms() throws Exception {
        // audit P2 (spec §20): the assistant DRAFTS from the member's own words — verbatim,
        // keyword-classified, confirmationRequired=true permanently — and writes NOTHING.
        String words = "I was charged GHS 50 twice for a lost book fine after I already paid. "
                + "Please refund one payment.";
        MvcResult draft = mvc.perform(api(post("/api/v1/tenant/ai/issues/draft"), reader)
                        .content("{\"description\":\"" + words + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationRequired").value(true))
                .andExpect(jsonPath("$.category").value("PAYMENTS"))
                .andExpect(jsonPath("$.description").value(words))   // echoed verbatim, never mutated
                .andReturn();
        String subject = TestApi.readJson(draft).path("subject").asText();
        assertThat(subject).startsWith("I was charged");
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM issues WHERE tenant_id = ?",
                Integer.class, tenantId);
        assertThat(rows).isZero();                                    // drafting created nothing

        // only the reporter's explicit confirm (client posts source=AI) opens the issue
        MvcResult created = mvc.perform(api(post("/api/v1/tenant/issues"), reader)
                        .content("{\"category\":\"PAYMENTS\",\"subject\":\"" + subject.replace("\"", "'")
                                + "\",\"description\":\"" + words + "\",\"source\":\"AI\","
                                + "\"clientKey\":\"ai-confirm-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("AI"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn();
        assertThat(TestApi.readJson(created).path("ref").asText()).matches("LN-\\d{6}");
        rows = jdbc.queryForObject("SELECT COUNT(*) FROM issues WHERE tenant_id = ?",
                Integer.class, tenantId);
        assertThat(rows).isEqualTo(1);

        // the chat lane steers problem reports at the draft, with no fabrication promise
        mvc.perform(api(post("/api/v1/tenant/ai/assistant/ask"), reader)
                        .content("{\"question\":\"I need help — something is broken with my account\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("REPORT_ISSUE"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("confirm")));

        // staff side sees the inbox in words too
        mvc.perform(api(post("/api/v1/tenant/ai/assistant/ask"), admin)
                        .content("{\"question\":\"Any support tickets waiting on us?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("SUPPORT_INBOX"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("1 open")));
    }
}
