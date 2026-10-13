package com.jotheca.authors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.jotheca.seed.DevDataSeeder;
import com.jotheca.support.TestApi;
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
 * Audit-P3 author capability (spec: author coexists with patron — capability, not account).
 * Privacy is the contract being tested: pen name out, legal identity never. The seeded
 * "Auntie Esi Writes" personal library stays untouched (legacy) — its books just gain a
 * profile link the suite asserts on.
 */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthorFlowTest {

    @Autowired MockMvc mvc;

    String writer;      // fresh account that turns the capability on
    String writerSlug;

    @BeforeAll
    void setup() throws Exception {
        String email = "writer-" + System.nanoTime() + "@jotheca.dev";
        TestApi.register(mvc, email, "WriterPass12345", "Kojo Mensah Fresh");
        writer = TestApi.login(mvc, email, "WriterPass12345");
    }

    @Test
    @Order(1)
    void capabilityIsOffUntilOpened() throws Exception {
        mvc.perform(get("/api/v1/me/author-profile")
                        .header("Authorization", TestApi.bearer(writer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUTHOR_PROFILE_REQUIRED"));
        mvc.perform(get("/api/v1/me/author-studio")
                        .header("Authorization", TestApi.bearer(writer)))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(2)
    void openingTheCapabilityNeedsOnlyAPenName() throws Exception {
        // same account, same login — no second registration, no payout questions (rule #15)
        MvcResult r = mvc.perform(put("/api/v1/me/author-profile")
                        .header("Authorization", TestApi.bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"penName\":\"Kwabena Ink\",\"bio\":\"Writes about rivers.\","
                                + "\"country\":\"Ghana\",\"languages\":\"English, Ga\","
                                + "\"genres\":\"Poetry, Essays\",\"authorType\":\"INDIE\","
                                + "\"links\":[{\"label\":\"Site\",\"url\":\"https://ink.example.com\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicProfile").value(false))
                .andExpect(jsonPath("$.sellingEnabled").value(false))
                .andReturn();
        writerSlug = TestApi.readJson(r).path("slug").asText();
        assertThat(writerSlug).startsWith("kwabena-ink");
        // unpublished = invisible to the public
        mvc.perform(get("/api/v1/public/authors/" + writerSlug))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(3)
    void profileInputIsValidated() throws Exception {
        mvc.perform(put("/api/v1/me/author-profile")
                        .header("Authorization", TestApi.bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"links\":[{\"label\":\"x\",\"url\":\"javascript:alert(1)\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINK_INVALID"));
        mvc.perform(put("/api/v1/me/author-profile")
                        .header("Authorization", TestApi.bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorType\":\"BESTSELLER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTHOR_TYPE_INVALID"));
    }

    @Test
    @Order(4)
    void publicPagePublishesOnlyOptedInFields() throws Exception {
        mvc.perform(put("/api/v1/me/author-profile")
                        .header("Authorization", TestApi.bearer(writer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicProfile\":true}"))
                .andExpect(status().isOk());
        MvcResult pub = mvc.perform(get("/api/v1/public/authors/" + writerSlug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.penName").value("Kwabena Ink"))
                .andReturn();
        String body = pub.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Kojo Mensah Fresh");       // legal name never leaks
        assertThat(body).doesNotContain("@jotheca.dev");           // account email never leaks
    }

    @Test
    @Order(5)
    void seededAuthorPageListsHerBooksAcrossTheLibrary() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/public/authors/auntie-esi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.penName").value("Auntie Esi"))
                .andExpect(jsonPath("$.sellsDirectly").value(false))
                .andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(body).contains("The Market of Whispers").contains("Accra by Night")
                .contains("Auntie Esi Writes").contains("35000");   // GH₵350.00 sticker
        assertThat(body).doesNotContain("Marfo");                   // legal identity stays private
    }

    @Test
    @Order(6)
    void studioAggregatesOnlyRowsLinkedToTheOwnProfile() throws Exception {
        String author = TestApi.login(mvc, "author@jotheca.app", "Author12345!");
        JsonNode studio = TestApi.readJson(mvc.perform(get("/api/v1/me/author-studio")
                        .header("Authorization", TestApi.bearer(author)))
                .andExpect(status().isOk()).andReturn());
        assertThat(studio.path("totals").path("books").asInt()).isEqualTo(2);
        assertThat(studio.path("totals").path("currency").asText()).isEqualTo("GHS");
        assertThat(studio.path("totals").path("revenueMinor").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(studio.path("books").toString())
                .contains("The Market of Whispers").contains("Accra by Night")
                // studio ships aggregates, never member identities
                .doesNotContain("membershipNumber").doesNotContain("MBR-");
    }

    @Test
    @Order(7)
    void payoutIsCollectedOnlyAtActivationAndNeverEchoedRaw() throws Exception {
        String author = TestApi.login(mvc, "author@jotheca.app", "Author12345!");
        mvc.perform(post("/api/v1/me/author-profile/activate-selling")
                        .header("Authorization", TestApi.bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"momoNumber\":\"1234567890\",\"momoNetwork\":\"MTN\"}"))  // no leading 0
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MOMO_NUMBER_INVALID"));
        MvcResult r = mvc.perform(post("/api/v1/me/author-profile/activate-selling")
                        .header("Authorization", TestApi.bearer(author))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"momoNumber\":\"0244123456\",\"momoNetwork\":\"MTN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellingEnabled").value(true))
                .andReturn();
        String masked = TestApi.readJson(r).path("payoutMomoMasked").asText();
        assertThat(masked).endsWith("456").hasSizeLessThan(10);  // masked, never the raw digits
        assertThat(r.getResponse().getContentAsString()).doesNotContain("0244123456");
        // …and selling shows up on the public page as a capability flag
        mvc.perform(get("/api/v1/public/authors/auntie-esi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellsDirectly").value(true));
        mvc.perform(post("/api/v1/me/author-profile/deactivate-selling")
                        .header("Authorization", TestApi.bearer(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellingEnabled").value(false));
    }

    @Test
    @Order(8)
    void bookProfileLinkingStaysAStaffMove() throws Exception {
        String author = TestApi.login(mvc, "author@jotheca.app", "Author12345!");
        // find the seeded book in her own tenant catalog
        JsonNode list = TestApi.readJson(mvc.perform(get("/api/v1/tenant/catalog/books?q=Whispers")
                        .header("Authorization", TestApi.bearer(author))
                        .header("X-Library-Id", DevDataSeeder.AUTHOR_UID))
                .andExpect(status().isOk()).andReturn());
        JsonNode bookSummary = list.path("content").get(0);
        JsonNode book = TestApi.readJson(mvc.perform(get("/api/v1/tenant/catalog/books/"
                        + bookSummary.path("id").asText())
                        .header("Authorization", TestApi.bearer(author))
                        .header("X-Library-Id", DevDataSeeder.AUTHOR_UID))
                .andExpect(status().isOk()).andReturn());
        assertThat(book.path("authorProfileId").asText()).isNotBlank(); // seeded E18 link

        // a plain patron of ANOTHER library cannot perform a catalog link anywhere
        String patron = TestApi.login(mvc, "patron@jotheca.app", "Patron12345!");
        mvc.perform(put("/api/v1/tenant/catalog/books/" + book.path("id").asText())
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Whispers\",\"author\":\"Esi Marfo\"}"))
                .andExpect(status().isForbidden());
    }
}
