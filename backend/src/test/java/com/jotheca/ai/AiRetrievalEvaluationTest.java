package com.jotheca.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.jotheca.seed.DevDataSeeder;
import com.jotheca.support.TestApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
 * Proportionate, reproducible retrieval-quality evaluation for semantic search (audit 2026-10-10).
 *
 * <p>Seeds a tiny, topically-separated catalog in the Ashesi tenant, issues a few labeled queries,
 * and computes classic IR metrics — Precision@5, Recall@10, nDCG@10 — against the deterministic
 * local embedder (so results are reproducible run-to-run). Metrics are printed to stdout so the
 * numbers can be read from the test log; only robust invariants are asserted (a relevant book is
 * retrieved near the top). Cross-tenant isolation of retrieval is asserted separately by the RLS /
 * tenant-isolation suite and by {@code AiFlowTest}; this harness measures ranking quality.
 *
 * <p><b>UNVERIFIED:</b> not executed in the audit environment (no JDK/Maven). Run with
 * {@code mvn -Dtest=AiRetrievalEvaluationTest test} to reproduce.
 */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiRetrievalEvaluationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.jotheca.tenancy.repo.LibraryRepository libraries;

    private String admin;
    private String tenantUid;
    private String tenantId;

    // labeled mini-catalog: id -> title (topics kept distinct from other suites' books)
    private final Map<String, String> books = new LinkedHashMap<>();

    @BeforeAll
    void setup() throws Exception {
        tenantUid = DevDataSeeder.ASHESI_UID;
        tenantId = libraries.findByUid(tenantUid).orElseThrow().getId();
        long stamp = System.nanoTime();
        String adminEmail = "ai-eval-" + stamp + "@jotheca.dev";
        TestApi.register(mvc, adminEmail, "AiEval12345", "AI Eval");
        admin = TestApi.login(mvc, adminEmail, "AiEval12345");
        mvc.perform(post("/api/v1/libraries/ashesi/join")
                        .header("Authorization", TestApi.bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + DevDataSeeder.ASHESI_INVITE_CODE + "\"}"))
                .andExpect(status().isOk());
        jdbc.update("UPDATE memberships SET role = 'LIBRARY_ADMIN' WHERE tenant_id = ?"
                + " AND user_id = (SELECT id FROM users WHERE email = ?)", tenantId, adminEmail);
    }

    private MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder b) {
        return b.header("Authorization", TestApi.bearer(admin))
                .header("X-Library-Id", tenantUid)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String createBook(String title, String author, String year,
                              String subjects, String description) throws Exception {
        MvcResult r = mvc.perform(api(post("/api/v1/tenant/catalog/books"))
                        .content("{\"title\":\"" + title + "\",\"author\":\"" + author + "\","
                                + "\"publishedDate\":\"" + year + "\",\"language\":\"English\","
                                + "\"subjects\":\"" + subjects + "\",\"description\":\""
                                + description + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return TestApi.readJson(r).path("id").asText();
    }

    @Test
    @Order(1)
    @DisplayName("Seed a small, topically-separated labeled catalog")
    void seedCatalog() throws Exception {
        books.put(createBook("Astrophysics of Black Holes", "N. Osei", "2021", "astrophysics cosmology",
                        "Black hole thermodynamics, event horizons and gravitational waves."),
                "Astrophysics of Black Holes");
        books.put(createBook("West African Drumming Traditions", "A. Mensah", "2019", "music ethnomusicology",
                        "Field study of djembe and talking-drum traditions across West Africa."),
                "West African Drumming Traditions");
        books.put(createBook("Quantum Computing Primer", "K. Boateng", "2022", "quantum computing",
                        "Qubits, quantum gates and Shor's algorithm explained for newcomers."),
                "Quantum Computing Primer");
        books.put(createBook("Coastal Erosion and Climate", "E. Adjei", "2020", "environment climate",
                        "How rising seas reshape coastlines, with case studies and mitigation."),
                "Coastal Erosion and Climate");
        books.put(createBook("Neural Networks from Scratch", "Y. Darko", "2023", "machine learning",
                        "Build perceptrons and backpropagation by hand, without frameworks."),
                "Neural Networks from Scratch");
        assertThat(books).hasSize(5);
    }

    /** query -> the single book id expected to be relevant */
    private Map<String, String> labeledQueries() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("black hole event horizon thermodynamics", idOf("Astrophysics of Black Holes"));
        q.put("talking drum djembe West Africa", idOf("West African Drumming Traditions"));
        q.put("quantum gates qubits algorithm", idOf("Quantum Computing Primer"));
        return q;
    }

    private String idOf(String title) {
        return books.entrySet().stream().filter(e -> e.getValue().equals(title))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private List<String> rankedBookIds(String query) throws Exception {
        MvcResult r = mvc.perform(api(get("/api/v1/tenant/ai/search").param("q", query)))
                .andExpect(status().isOk()).andReturn();
        JsonNode hits = TestApi.readJson(r).path("hits");
        List<String> ids = new ArrayList<>();
        for (JsonNode h : hits) ids.add(h.path("bookId").asText());
        return ids;
    }

    private int rankOf(List<String> ranked, String relevantId) {
        return ranked.indexOf(relevantId) + 1; // 1-based; 0 = not retrieved
    }

    @Test
    @Order(2)
    @DisplayName("Retrieval quality: Precision@5 / Recall@10 / nDCG@10 (reported; robust asserts)")
    void evaluateRetrievalQuality() throws Exception {
        StringBuilder report = new StringBuilder("\n=== AI semantic-search retrieval evaluation ===\n");
        double sumNdcg = 0;
        int n = 0;
        for (Map.Entry<String, String> e : labeledQueries().entrySet()) {
            String query = e.getKey();
            String relevantId = e.getValue();
            List<String> ranked = rankedBookIds(query);
            assertThat(ranked).as("query '%s' must return >=1 hit", query).isNotEmpty();

            int rank = rankOf(ranked, relevantId);           // 1-based, 0 if absent
            double precisionAt5 = (rank >= 1 && rank <= 5) ? 1.0 / 5.0 : 0.0;   // 1 relevant doc
            double recallAt10 = (rank >= 1 && rank <= 10) ? 1.0 : 0.0;
            double ndcgAt10 = (rank >= 1) ? 1.0 / (Math.log(rank + 1) / Math.log(2)) : 0.0;
            sumNdcg += ndcgAt10;
            n++;

            report.append(String.format(
                    "q=%-42s relevantRank=%-3d P@5=%.2f R@10=%.2f nDCG@10=%.3f%n",
                    "\"" + query + "\"", rank, precisionAt5, recallAt10, ndcgAt10));

            // robust invariants: the labeled book is retrieved near the top for a specific query
            assertThat(rank).as("relevant book for '%s' should be in top-3", query)
                    .isBetween(1, 3);
        }
        // the most specific query should rank its book #1
        assertThat(rankOf(rankedBookIds("black hole event horizon thermodynamics"),
                idOf("Astrophysics of Black Holes"))).isEqualTo(1);

        report.append(String.format("mean nDCG@10 over %d queries = %.3f%n", n, sumNdcg / n));
        System.out.println(report);
    }
}
