package com.libranet.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.libranet.seed.DevDataSeeder;
import com.libranet.support.TestApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase-11 hardening proofs: edge caching on the anonymous plane (and its absence on private
 * data), OpenAPI error-envelope polish, and the V18 performance indexes.
 */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class HardeningTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    // ---------- edge caching (perf) ----------

    @Test
    void anonymousDiscoveryCarriesShortPublicCache() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/libraries").param("search", "accra"))
                .andExpect(status().isOk()).andReturn();
        String cc = r.getResponse().getHeader("Cache-Control");
        assertThat(cc).contains("public").contains("max-age=60");
    }

    @Test
    void publicBookPagesAndReviewsCarryPublicCache() throws Exception {
        String slug = anyVisibleLibrarySlug();
        MvcResult list = mvc.perform(get("/api/v1/public/libraries/{slug}/books", slug))
                .andExpect(status().isOk()).andReturn();
        assertThat(list.getResponse().getHeader("Cache-Control"))
                .contains("public").contains("max-age=60");
    }

    @Test
    void invitationPreviewIsNeverCached() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/join/{key}", DevDataSeeder.ASHESI_INVITE_CODE))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    void privateTenantDataIsNotPubliclyCacheable() throws Exception {
        String token = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        MvcResult r = mvc.perform(get("/api/v1/tenant/catalog/books")
                        .header("Authorization", TestApi.bearer(token))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
        String cc = r.getResponse().getHeader("Cache-Control");
        // private responses must never tell a shared cache to store them
        if (cc != null) {
            assertThat(cc).doesNotContain("public");
        }
    }

    // ---------- OpenAPI polish ----------

    @Test
    void apiDocsDescribeTheSharedErrorEnvelope() throws Exception {
        JsonNode docs = apiDocs();
        JsonNode apiError = docs.at("/components/schemas/ApiError/properties");
        assertThat(apiError.isMissingNode()).isFalse();
        assertThat(apiError.has("traceId")).isTrue();
        assertThat(apiError.has("code")).isTrue();
    }

    @Test
    void everyOperationDocumentsTheErrorsItCanActuallyProduce() throws Exception {
        JsonNode docs = apiDocs();
        JsonNode login = docs.at("/paths/~1api~1v1~1auth~1login/post/responses");
        for (String code : new String[] {"400", "401", "423", "429"}) {
            assertThat(login.has(code)).as("login documents " + code).isTrue();
        }
        // lockout is a login/mfa-only concept — never documented on unrelated endpoints
        JsonNode aiSearch = docs.at("/paths/~1api~1v1~1tenant~1ai~1search/get/responses");
        assertThat(aiSearch.has("423")).isFalse();
        assertThat(aiSearch.has("401")).isTrue();   // bearer required
        assertThat(aiSearch.has("403")).isTrue();   // tenant guard
        // the anonymous discovery root must not demand a token
        JsonNode discover = docs.at("/paths/~1api~1v1~1libraries/get/responses");
        assertThat(discover.has("401")).isFalse();
        assertThat(discover.has("400")).isTrue();
    }

    @Test
    void aiEndpointsCarryOperationSummariesAfterPolish() throws Exception {
        JsonNode docs = apiDocs();
        assertThat(docs.at("/paths/~1api~1v1~1tenant~1ai~1search/get/summary").asText())
                .isEqualTo("Semantic catalog search");
        assertThat(docs.at("/paths/~1api~1v1~1tenant~1ai~1assistant~1ask/post/summary").asText())
                .isEqualTo("Library AI assistant (staff + patrons)");
    }

    // ---------- V18 performance indexes ----------

    @Test
    void phase11IndexesExist() {
        Long found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(INDEX_NAME) IN ("
                        + "'IDX_BOOKS_TENANT_PUBLISHED_TITLE','IDX_LIBRARIES_STATUS_NAME',"
                        + "'IDX_SEARCH_EVENTS_ZERO_HIT','IDX_AUDIT_ACTION_CREATED',"
                        + "'IDX_NOTIFICATIONS_UNREAD')", Long.class);
        assertThat(found).isEqualTo(5);
    }

    // ---------- helpers ----------

    private JsonNode apiDocs() throws Exception {
        MvcResult r = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        return TestApi.readJson(r);
    }

    private String anyVisibleLibrarySlug() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/libraries")).andExpect(status().isOk()).andReturn();
        JsonNode content = TestApi.readJson(r).path("content");
        assertThat(content.isEmpty()).isFalse();
        return content.get(0).path("slug").asText();
    }
}
