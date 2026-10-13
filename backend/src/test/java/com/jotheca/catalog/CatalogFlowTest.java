package com.jotheca.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * Phase 4 happy-path suite: seeded catalog browse → staff CRUD of books/copies/categories/branches/
 * shelf locations → search filters → entitlement (branch cap) enforcement.
 * Uses the seeded Accra staff (accraadmin) and patron; isolation gets its own cases in TenantIsolationTest.
 */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogFlowTest {

    @Autowired MockMvc mvc;

    // ---------- seeded catalog ----------

    @Test
    @Order(1)
    void patronBrowsesSeededCatalogOfOwnLibrary() throws Exception {
        String patron = TestApi.login(mvc, "patron@jotheca.app", "Patron12345!");
        MvcResult result = mvc.perform(get("/api/v1/tenant/catalog/books")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = TestApi.readJson(result);
        assertThat(page.path("page").path("totalElements").asLong()).isEqualTo(8);
        for (JsonNode book : page.path("content")) {
            assertThat(book.path("title").asText()).isNotBlank();
            assertThat(book.path("availableCopies").asLong()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @Order(2)
    void searchByQueryAuthorIsbnAndLanguage() throws Exception {
        String patron = TestApi.login(mvc, "patron@jotheca.app", "Patron12345!");
        // q matches title
        mvc.perform(get("/api/v1/tenant/catalog/books?q=things fall")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].author").value("Chinua Achebe"));
        // q matches author
        mvc.perform(get("/api/v1/tenant/catalog/books?q=harari")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title")
                        .value("Sapiens: A Brief History of Humankind"));
        // ISBN lookup normalizes hyphens
        mvc.perform(get("/api/v1/tenant/catalog/books?isbn=978-0-13-235088-4")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Clean Code"));
        // language filter on the seeded English catalog
        mvc.perform(get("/api/v1/tenant/catalog/books?language=english")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(8));
    }

    @Test
    @Order(3)
    void categorySubtreeSearchShowsChildTitles() throws Exception {
        String patron = TestApi.login(mvc, "patron@jotheca.app", "Patron12345!");
        MvcResult tree = mvc.perform(get("/api/v1/tenant/catalog/categories")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andReturn();
        String fictionId = null;
        for (JsonNode node : TestApi.readJson(tree)) {
            if ("Fiction".equals(node.path("name").asText())) {
                fictionId = node.path("id").asText();
            }
        }
        assertThat(fictionId).isNotNull();
        // all 4 African-literature books sit in the Fiction subtree
        mvc.perform(get("/api/v1/tenant/catalog/books?categoryId=" + fictionId)
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(4));
    }

    @Test
    @Order(4)
    void bookDetailIncludesAvailabilityCounts() throws Exception {
        String patron = TestApi.login(mvc, "patron@jotheca.app", "Patron12345!");
        String thingsId = firstBookId(patron, "Things Fall Apart");
        mvc.perform(get("/api/v1/tenant/catalog/books/" + thingsId)
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Things Fall Apart"))
                // 3 copies: 2 AVAILABLE + 1 DAMAGED (damaged counts toward holdings, not availability)
                .andExpect(jsonPath("$.totalCopies").value(3))
                .andExpect(jsonPath("$.availableCopies").value(2));
    }

    // ---------- staff CRUD ----------

    @Test
    @Order(5)
    void staffManagesBookLifecycleAndCopies() throws Exception {
        String admin = TestApi.login(mvc, "accraadmin@jotheca.app", "Library12345!");
        // category
        MvcResult cat = mvc.perform(post("/api/v1/tenant/catalog/categories")
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Poetry\",\"kind\":\"Poetry\",\"sortOrder\":9}"))
                .andExpect(status().isCreated())
                .andReturn();
        String categoryId = TestApi.readJson(cat).path("id").asText();

        // book
        MvcResult created = mvc.perform(post("/api/v1/tenant/catalog/books")
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"The Sun and Her Flowers\",\"author\":\"Rupi Kaur\","
                                + "\"isbn\":\"978-1-51-116523-4\",\"publisher\":\"Andrews McMeel\","
                                + "\"publishedDate\":\"2017\",\"language\":\"English\","
                                + "\"categoryId\":\"" + categoryId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isbn").value("9781511165234")) // normalized
                .andExpect(jsonPath("$.categoryName").value("Poetry"))
                .andReturn();
        String bookId = TestApi.readJson(created).path("id").asText();
        assertThat(TestApi.readJson(created).path("slug").asText()).startsWith("the-sun-and-her-flowers-");

        // duplicate ISBN in the same tenant → 409
        mvc.perform(post("/api/v1/tenant/catalog/books")
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Another Poetry Book\",\"author\":\"Someone Else\","
                                + "\"isbn\":\"9781511165234\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ISBN_TAKEN"));

        // invalid ISBN → 400
        mvc.perform(post("/api/v1/tenant/catalog/books")
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Bad ISBN\",\"author\":\"X\",\"isbn\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ISBN"));

        // update
        mvc.perform(put("/api/v1/tenant/catalog/books/" + bookId)
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"The Sun and Her Flowers\",\"author\":\"Rupi Kaur\","
                                + "\"isbn\":\"9781511165234\",\"subjects\":\"poetry, love\","
                                + "\"categoryId\":\"" + categoryId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjects").value("poetry, love"));

        // copies: barcode generated from the main-branch code (AC-000014 is next in the sequence)
        MvcResult copy1 = mvc.perform(post("/api/v1/tenant/catalog/books/" + bookId + "/copies")
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.barcode").value("AC-000014"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andReturn();
        String copyId = TestApi.readJson(copy1).path("id").asText();

        mvc.perform(post("/api/v1/tenant/catalog/books/" + bookId + "/copies")
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"barcode\":\"AC-000015\",\"accessionNumber\":\"ACC-9000\"}"))
                .andExpect(status().isCreated());

        // available count goes to 2
        mvc.perform(get("/api/v1/tenant/catalog/books/" + bookId)
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCopies").value(2))
                .andReturn();

        // status transitions; BORROWED is reserved for circulation
        mvc.perform(patch("/api/v1/tenant/catalog/copies/" + copyId + "/status")
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"MAINTENANCE\",\"notes\":\"spine repair\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MAINTENANCE"));
        mvc.perform(patch("/api/v1/tenant/catalog/copies/" + copyId + "/status")
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BORROWED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STATUS_VIA_CIRCULATION"));

        // listing shows 2 copies
        MvcResult list = mvc.perform(get("/api/v1/tenant/catalog/books/" + bookId + "/copies")
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(TestApi.readJson(list)).hasSize(2);

        // delete guard: book with copies refuses
        mvc.perform(delete("/api/v1/tenant/catalog/books/" + bookId)
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOK_HAS_COPIES"));

        // remove copies then the book
        for (JsonNode c : TestApi.readJson(list)) {
            mvc.perform(delete("/api/v1/tenant/catalog/copies/" + c.path("id").asText())
                            .header("Authorization", TestApi.bearer(admin))
                            .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(delete("/api/v1/tenant/catalog/books/" + bookId)
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/tenant/catalog/books/" + bookId)
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isNotFound());

        // category deletable once unused
        mvc.perform(delete("/api/v1/tenant/catalog/categories/" + categoryId)
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isNoContent());
    }

    @Test
    @Order(6)
    void unpublishedBooksAreHiddenFromPatronsButVisibleToStaff() throws Exception {
        String admin = TestApi.login(mvc, "accraadmin@jotheca.app", "Library12345!");
        MvcResult created = mvc.perform(post("/api/v1/tenant/catalog/books")
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Draft Acquisition Dossier\",\"author\":\"Internal\","
                                + "\"published\":false}"))
                .andExpect(status().isCreated())
                .andReturn();
        String hiddenId = TestApi.readJson(created).path("id").asText();

        String patron = TestApi.login(mvc, "patron@jotheca.app", "Patron12345!");
        // invisible in search even if the patron asks for unpublished…
        mvc.perform(get("/api/v1/tenant/catalog/books?includeUnpublished=true&q=dossier")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
        // …and 404 (not 403) on direct access
        mvc.perform(get("/api/v1/tenant/catalog/books/" + hiddenId)
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isNotFound());
        // staff sees it
        mvc.perform(get("/api/v1/tenant/catalog/books/" + hiddenId)
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk());
    }

    @Test
    @Order(7)
    void shelfLocationsRenderSpecDisplayFormat() throws Exception {
        String admin = TestApi.login(mvc, "accraadmin@jotheca.app", "Library12345!");
        MvcResult locations = mvc.perform(get("/api/v1/tenant/catalog/shelf-locations")
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andReturn();
        boolean found = false;
        for (JsonNode loc : TestApi.readJson(locations)) {
            if ("A-12".equals(loc.path("shelf").asText())) {
                assertThat(loc.path("display").asText()).isEqualTo("Shelf A-12, Floor 2, African Literature");
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @Order(8)
    void accraStarterPlanCapsBranchesAtOne() throws Exception {
        String admin = TestApi.login(mvc, "accraadmin@jotheca.app", "Library12345!");
        // Accra is on STARTER (maxBranches=1) and already has its Main Branch
        mvc.perform(post("/api/v1/tenant/catalog/branches")
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Osu Community Branch\",\"code\":\"OS\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAN_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.details.resource").value("branches"))
                .andExpect(jsonPath("$.details.limit").value(1));
    }

    @Test
    @Order(9)
    void patronCannotWriteCatalog() throws Exception {
        String patron = TestApi.login(mvc, "patron@jotheca.app", "Patron12345!");
        mvc.perform(post("/api/v1/tenant/catalog/books")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Nope\",\"author\":\"Nope\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_LIBRARY_ROLE"));
        // patrons also don't get the raw copy register (barcodes are staff data)
        String thingsId = firstBookId(patron, "Things Fall Apart");
        mvc.perform(get("/api/v1/tenant/catalog/books/" + thingsId + "/copies")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(10)
    void pentecostHasItsOwnSeededCatalog() throws Exception {
        String staff = TestApi.login(mvc, "staff@jotheca.app", "Library12345!");
        // default view: published only (2 of the 3 seeded Pentecost titles)
        mvc.perform(get("/api/v1/tenant/catalog/books")
                        .header("Authorization", TestApi.bearer(staff))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));
        // staff opt-in sees the unpublished review copy too
        mvc.perform(get("/api/v1/tenant/catalog/books?includeUnpublished=true")
                        .header("Authorization", TestApi.bearer(staff))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(3));
    }

    // ---------- helpers ----------

    private String firstBookId(String token, String titleQuery) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/tenant/catalog/books?q=" + titleQuery)
                        .header("Authorization", TestApi.bearer(token))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = TestApi.readJson(result).path("content");
        assertThat(content).isNotEmpty();
        return content.get(0).path("id").asText();
    }
}
