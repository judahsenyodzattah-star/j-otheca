package com.jotheca.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.jotheca.seed.DevDataSeeder;
import com.jotheca.support.TestApi;
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
 * Phase 6 suite: public book pages (no auth), share URLs, reader interest tracking with author
 * signals, and physical-sale purchase requests (request → staff handover → copy leaves register).
 * Fixture: seeded PERSONAL library "auntie-esi-writes" (owner author@jotheca.app).
 */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PublishingFlowTest {

    @Autowired MockMvc mvc;

    // ---------- public book pages ----------

    @Test
    @Order(1)
    void publicCatalogIsBrowsableWithoutAuthentication() throws Exception {
        mvc.perform(get("/api/v1/public/libraries/auntie-esi-writes/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].slug").isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.availableCopies)]").exists());
        mvc.perform(get("/api/v1/public/libraries/accra-central/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(8));
        // public search
        mvc.perform(get("/api/v1/public/libraries/accra-central/books?q=harari"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Sapiens: A Brief History of Humankind"));
    }

    @Test
    @Order(2)
    void publicBookPageHasShareUrlPriceAndInterestCount() throws Exception {
        MvcResult list = mvc.perform(get("/api/v1/public/libraries/auntie-esi-writes/books"))
                .andExpect(status().isOk()).andReturn();
        String slug = null;
        for (JsonNode b : TestApi.readJson(list).path("content")) {
            if (b.path("title").asText().equals("The Market of Whispers")) {
                slug = b.path("slug").asText();
            }
        }
        assertThat(slug).isNotNull();
        mvc.perform(get("/api/v1/public/libraries/auntie-esi-writes/books/" + slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("The Market of Whispers"))
                .andExpect(jsonPath("$.salePriceMinor").value(35000))
                .andExpect(jsonPath("$.contactNote").value("Signed by the author at pickup."))
                .andExpect(jsonPath("$.libraryName").value("Auntie Esi Writes"))
                .andExpect(jsonPath("$.libraryOpenMembership").value(true))
                .andExpect(jsonPath("$.shareUrl").value(org.hamcrest.Matchers.endsWith(
                        "/book/auntie-esi-writes/" + slug)))
                .andExpect(jsonPath("$.availableCopies").value(2))
                .andExpect(jsonPath("$.interestedCount").value(0));
        // unknown combinations stay hidden
        mvc.perform(get("/api/v1/public/libraries/auntie-esi-writes/books/nope-1234"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/libraries/no-such-library/books/" + slug))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(3)
    void unpublishedBooksNeverAppearOnPublicPages() throws Exception {
        // Pentecost staff can see the hidden review copy; the public cannot.
        String staff = TestApi.login(mvc, "staff@jotheca.app", "Library12345!");
        MvcResult hidden = mvc.perform(get("/api/v1/tenant/catalog/books?includeUnpublished=true&q=Ethics")
                        .header("Authorization", TestApi.bearer(staff))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andReturn();
        String hiddenSlug = TestApi.readJson(hidden).path("content").get(0).path("slug").asText();
        mvc.perform(get("/api/v1/public/libraries/pentecost/books/" + hiddenSlug))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/libraries/pentecost/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    // ---------- interest ----------

    @Test
    @Order(4)
    void interestMarksAreIdempotentAndSignalTheAuthor() throws Exception {
        // a fresh reader joins the author library (PUBLIC policy)
        String email = "reader-" + System.nanoTime() + "@example.com";
        TestApi.register(mvc, email, "Password12345", "Curious Reader");
        String reader = TestApi.login(mvc, email, "Password12345");

        // find the book id as a public user → join → mark interest
        MvcResult list = mvc.perform(get("/api/v1/public/libraries/auntie-esi-writes/books"))
                .andReturn();
        String whispersId = null;
        for (JsonNode b : TestApi.readJson(list).path("content")) {
            if (b.path("title").asText().equals("The Market of Whispers")) {
                whispersId = b.path("slug").asText(); // id not exposed publicly; fetch via tenant detail
            }
        }
        mvc.perform(post("/api/v1/libraries/auntie-esi-writes/join")
                        .header("Authorization", TestApi.bearer(reader))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        JsonNode detail = TestApi.readJson(mvc.perform(
                        get("/api/v1/tenant/catalog/books?q=Whispers")
                                .header("Authorization", TestApi.bearer(reader))
                                .header("X-Library-Id", DevDataSeeder.AUTHOR_UID))
                .andExpect(status().isOk()).andReturn());
        whispersId = detail.path("content").get(0).path("id").asText();

        // mark → idempotent second mark → state
        mvc.perform(put("/api/v1/tenant/catalog/books/" + whispersId + "/interest")
                        .header("Authorization", TestApi.bearer(reader))
                        .header("X-Library-Id", DevDataSeeder.AUTHOR_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interested").value(true))
                .andExpect(jsonPath("$.interestedCount").value(1));
        mvc.perform(put("/api/v1/tenant/catalog/books/" + whispersId + "/interest")
                        .header("Authorization", TestApi.bearer(reader))
                        .header("X-Library-Id", DevDataSeeder.AUTHOR_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interestedCount").value(1));

        // the author got the demand signal
        String author = TestApi.login(mvc, "author@jotheca.app", "Author12345!");
        JsonNode feed = TestApi.readJson(mvc.perform(get("/api/v1/tenant/notifications/me")
                        .header("Authorization", TestApi.bearer(author))
                        .header("X-Library-Id", DevDataSeeder.AUTHOR_UID))
                .andExpect(status().isOk()).andReturn());
        assertThat(feed.path("content").toString()).contains("INTEREST_SIGNAL");

        // staff demand view lists the reader
        JsonNode demand = TestApi.readJson(mvc.perform(
                        get("/api/v1/tenant/catalog/books/" + whispersId + "/interest/members")
                                .header("Authorization", TestApi.bearer(author))
                                .header("X-Library-Id", DevDataSeeder.AUTHOR_UID))
                .andExpect(status().isOk()).andReturn());
        assertThat(demand.toString()).contains("Curious Reader");

        // unmark
        mvc.perform(delete("/api/v1/tenant/catalog/books/" + whispersId + "/interest")
                        .header("Authorization", TestApi.bearer(reader))
                        .header("X-Library-Id", DevDataSeeder.AUTHOR_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interested").value(false))
                .andExpect(jsonPath("$.interestedCount").value(0));
    }

    // ---------- physical sales ----------

    @Test
    @Order(5)
    void physicalSaleRequestFulfilledByStaffHandover() throws Exception {
        String author = TestApi.login(mvc, "author@jotheca.app", "Author12345!");
        // a buyer joins and requests the sale-priced title
        String email = "buyer-" + System.nanoTime() + "@example.com";
        TestApi.register(mvc, email, "Password12345", "Book Buyer");
        String buyer = TestApi.login(mvc, email, "Password12345");
        mvc.perform(post("/api/v1/libraries/auntie-esi-writes/join")
                        .header("Authorization", TestApi.bearer(buyer))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        String whispersId = TestApi.readJson(mvc.perform(get("/api/v1/tenant/catalog/books?q=Whispers")
                        .header("Authorization", TestApi.bearer(buyer))
                        .header("X-Library-Id", DevDataSeeder.AUTHOR_UID))
                .andReturn()).path("content").get(0).path("id").asText();

        MvcResult req = mvc.perform(post("/api/v1/tenant/catalog/books/" + whispersId + "/purchase")
                        .header("Authorization", TestApi.bearer(buyer))
                        .header("X-Library-Id", DevDataSeeder.AUTHOR_UID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"for my mum\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.priceMinor").value(35000))
                .andReturn();
        String requestId = TestApi.readJson(req).path("id").asText();

        // duplicate pending request blocked
        mvc.perform(post("/api/v1/tenant/catalog/books/" + whispersId + "/purchase")
                        .header("Authorization", TestApi.bearer(buyer))
                        .header("X-Library-Id", DevDataSeeder.AUTHOR_UID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REQUESTED"));

        // staff queue → fulfill by scanning the barcode
        JsonNode queue = TestApi.readJson(mvc.perform(get("/api/v1/tenant/purchases")
                        .header("Authorization", TestApi.bearer(author))
                        .header("X-Library-Id", DevDataSeeder.AUTHOR_UID))
                .andExpect(status().isOk()).andReturn());
        assertThat(queue.toString()).contains(requestId);
        mvc.perform(post("/api/v1/tenant/purchases/" + requestId + "/fulfill?barcode=AW-000001")
                        .header("Authorization", TestApi.bearer(author))
                        .header("X-Library-Id", DevDataSeeder.AUTHOR_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"))
                .andExpect(jsonPath("$.copyBarcode").value("AW-000001"));

        // sold copy leaves the register (REMOVED, never deleted) → public availability drops
        JsonNode copies = TestApi.readJson(mvc.perform(
                        get("/api/v1/tenant/catalog/books/" + whispersId + "/copies")
                                .header("Authorization", TestApi.bearer(author))
                                .header("X-Library-Id", DevDataSeeder.AUTHOR_UID))
                .andReturn());
        for (JsonNode c : copies) {
            if ("AW-000001".equals(c.path("barcode").asText())) {
                assertThat(c.path("status").asText()).isEqualTo("REMOVED");
            }
        }
        mvc.perform(get("/api/v1/public/libraries/auntie-esi-writes/books?q=Whispers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].availableCopies").value(1));
    }

    @Test
    @Order(6)
    void titlesWithoutAPriceCannotBePurchased() throws Exception {
        String patron = TestApi.login(mvc, "patron@jotheca.app", "Patron12345!");
        String sapiensId = TestApi.readJson(mvc.perform(get("/api/v1/tenant/catalog/books?q=Sapiens")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andReturn()).path("content").get(0).path("id").asText();
        mvc.perform(post("/api/v1/tenant/catalog/books/" + sapiensId + "/purchase")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_FOR_SALE"));
    }
}
