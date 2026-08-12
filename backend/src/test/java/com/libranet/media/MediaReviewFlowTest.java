package com.libranet.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.libranet.billing.service.EntitlementService;
import com.libranet.common.error.ApiException;
import com.libranet.seed.DevDataSeeder;
import com.libranet.support.TestApi;
import com.libranet.tenancy.repo.LibraryRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase-7 flow: ratings → reviews → moderation → reports/helpful → media uploads → quota.
 * Everything happens at Accra Central (patron / reader2 / accraadmin are its members);
 * fixtures of other suites are never mutated. Ordered, one shared Spring context.
 */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MediaReviewFlowTest {

    private static final String ACCRA = DevDataSeeder.ACCRA_UID;
    private static final String ACCRA_SLUG = "accra-central";

    @Autowired MockMvc mvc;
    @Autowired EntitlementService entitlements;
    @Autowired LibraryRepository libraries;

    String patron;      // Efua — Accra patron
    String admin;       // Yaw — Accra LIBRARY_ADMIN
    String reader2;     // freshly joined Accra public member
    String sapiensId;
    String sapiensSlug;
    String reviewId;
    String coverMediaId;

    @BeforeAll
    void setup() throws Exception {
        patron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        admin = TestApi.login(mvc, "accraadmin@libranet.app", "Library12345!");
        TestApi.register(mvc, "p7reader@libranet.app", "Reader12345!", "Kofi Reader");
        reader2 = TestApi.login(mvc, "p7reader@libranet.app", "Reader12345!");
        // Accra join policy is PUBLIC → instant approval
        mvc.perform(post("/api/v1/libraries/" + ACCRA_SLUG + "/join")
                        .header("Authorization", TestApi.bearer(reader2)))
                .andExpect(status().isOk());

        JsonNode search = TestApi.readJson(mvc.perform(api(get("/api/v1/tenant/catalog/books?q=Sapiens"), patron))
                .andExpect(status().isOk()).andReturn());
        sapiensId = search.path("content").get(0).path("id").asText();
        sapiensSlug = search.path("content").get(0).path("slug").asText();
        assertThat(sapiensId).isNotBlank();
    }

    // ---------- ratings ----------

    @Test @Order(1)
    void ratingUpsertMaintainsAggregates() throws Exception {
        mvc.perform(api(put("/api/v1/tenant/catalog/books/{id}/rating", sapiensId), patron)
                        .content("{\"stars\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myStars").value(4))
                .andExpect(jsonPath("$.ratingAvg").value(4.0))
                .andExpect(jsonPath("$.ratingCount").value(1));
        // re-rate updates in place — still ONE rating
        mvc.perform(api(put("/api/v1/tenant/catalog/books/{id}/rating", sapiensId), patron)
                        .content("{\"stars\":5}"))
                .andExpect(jsonPath("$.myStars").value(5))
                .andExpect(jsonPath("$.ratingAvg").value(5.0))
                .andExpect(jsonPath("$.ratingCount").value(1));
        mvc.perform(api(get("/api/v1/tenant/catalog/books/{id}/rating", sapiensId), patron))
                .andExpect(jsonPath("$.myStars").value(5));
    }

    @Test @Order(2)
    void ratingOutsideRangeFailsValidation() throws Exception {
        mvc.perform(api(put("/api/v1/tenant/catalog/books/{id}/rating", sapiensId), patron)
                        .content("{\"stars\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- reviews ----------

    @Test @Order(3)
    void anonymousReviewRequiresLibraryPolicy() throws Exception {
        mvc.perform(api(put("/api/v1/tenant/review-policy"), admin)
                        .content("{\"allowAnonymousReviews\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowAnonymousReviews").value(false));
        mvc.perform(api(post("/api/v1/tenant/catalog/books/{id}/reviews", sapiensId), patron)
                        .content("{\"body\":\"Great but anonymous\",\"anonymous\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ANONYMOUS_REVIEWS_NOT_ALLOWED"));
        mvc.perform(api(put("/api/v1/tenant/review-policy"), admin)
                        .content("{\"allowAnonymousReviews\":true}"))
                .andExpect(jsonPath("$.allowAnonymousReviews").value(true));

        MvcResult created = mvc.perform(api(post("/api/v1/tenant/catalog/books/{id}/reviews", sapiensId), patron)
                        .content("{\"title\":\"Mind-expanding\",\"body\":\"A sweeping history of humankind.\",\"anonymous\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        reviewId = TestApi.readJson(created).path("id").asText();

        // one review per member per book
        mvc.perform(api(post("/api/v1/tenant/catalog/books/{id}/reviews", sapiensId), patron)
                        .content("{\"body\":\"second attempt\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_EXISTS"));
    }

    @Test @Order(4)
    void pendingReviewVisibleToAuthorAndStaffOnly() throws Exception {
        // author sees own pending review
        mvc.perform(api(get("/api/v1/tenant/catalog/books/{id}/reviews", sapiensId), patron))
                .andExpect(jsonPath("$.content[0].id").value(reviewId))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].mine").value(true));
        // another member does NOT see it
        mvc.perform(api(get("/api/v1/tenant/catalog/books/{id}/reviews", sapiensId), reader2))
                .andExpect(jsonPath("$.content.length()").value(0));
        // staff moderation queue shows it WITH the real identity despite the anonymous flag
        mvc.perform(api(get("/api/v1/tenant/reviews/moderation?status=PENDING"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + reviewId + "')].displayName")
                        .value(org.hamcrest.Matchers.contains("Efua Boateng")));
    }

    @Test @Order(5)
    void approvalPublishesReviewAnonymously() throws Exception {
        mvc.perform(api(post("/api/v1/tenant/reviews/{id}/approve", reviewId), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        // members now see it — anonymized
        mvc.perform(api(get("/api/v1/tenant/catalog/books/{id}/reviews", sapiensId), reader2))
                .andExpect(jsonPath("$.content[0].displayName").value("Anonymous member"))
                .andExpect(jsonPath("$.content[0].membershipNumber").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[0].mine").value(false));
        // public, unauthenticated book page feed shows it too
        mvc.perform(get("/api/v1/public/libraries/{slug}/books/{bookSlug}/reviews", ACCRA_SLUG, sapiensSlug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].displayName").value("Anonymous member"))
                .andExpect(jsonPath("$.content[0].body").value("A sweeping history of humankind."));
    }

    @Test @Order(6)
    void helpfulVotesToggleAndSelfVoteBlocked() throws Exception {
        mvc.perform(api(put("/api/v1/tenant/reviews/{id}/helpful", reviewId), reader2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helpful").value(true))
                .andExpect(jsonPath("$.helpfulCount").value(1));
        // idempotent: a second PUT does not double-count
        mvc.perform(api(put("/api/v1/tenant/reviews/{id}/helpful", reviewId), reader2))
                .andExpect(jsonPath("$.helpfulCount").value(1));
        mvc.perform(api(put("/api/v1/tenant/reviews/{id}/helpful", reviewId), patron))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_VOTE_OWN"));
        mvc.perform(api(delete("/api/v1/tenant/reviews/{id}/helpful", reviewId), reader2))
                .andExpect(jsonPath("$.helpful").value(false))
                .andExpect(jsonPath("$.helpfulCount").value(0));
    }

    @Test @Order(7)
    void reportAndRejectResolvesReports() throws Exception {
        mvc.perform(api(post("/api/v1/tenant/reviews/{id}/report", reviewId), reader2)
                        .content("{\"reason\":\"Contains spoilers without warning\"}"))
                .andExpect(status().isCreated());
        mvc.perform(api(post("/api/v1/tenant/reviews/{id}/report", reviewId), reader2)
                        .content("{\"reason\":\"duplicate\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_EXISTS"));
        mvc.perform(api(post("/api/v1/tenant/reviews/{id}/report", reviewId), patron)
                        .content("{\"reason\":\"mine\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_REPORT_OWN"));

        mvc.perform(api(post("/api/v1/tenant/reviews/{id}/reject", reviewId), admin)
                        .content("{\"note\":\"Please mark spoilers clearly.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.moderationNote").value("Please mark spoilers clearly."));
        // rejected review drops from other members' and public feeds; the author still sees it, with the note
        mvc.perform(api(get("/api/v1/tenant/catalog/books/{id}/reviews", sapiensId), reader2))
                .andExpect(jsonPath("$.content.length()").value(0));
        mvc.perform(api(get("/api/v1/tenant/catalog/books/{id}/reviews", sapiensId), patron))
                .andExpect(jsonPath("$.content[?(@.id == '" + reviewId + "')].status")
                        .value(org.hamcrest.Matchers.contains("REJECTED")));
        mvc.perform(get("/api/v1/public/libraries/{slug}/books/{bookSlug}/reviews", ACCRA_SLUG, sapiensSlug))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test @Order(8)
    void editResetsToPendingAndDeleteSoftRemoves() throws Exception {
        mvc.perform(api(patch("/api/v1/tenant/reviews/{id}", reviewId), patron)
                        .content("{\"title\":\"Mind-expanding (fixed)\",\"body\":\"A sweeping history of humankind. Spoiler-free.\",\"anonymous\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        // someone else's review id answers 404, even inside the same tenant
        mvc.perform(api(patch("/api/v1/tenant/reviews/{id}", reviewId), reader2)
                        .content("{\"body\":\"hijack\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(api(delete("/api/v1/tenant/reviews/{id}", reviewId), patron))
                .andExpect(status().isNoContent());
        mvc.perform(api(get("/api/v1/tenant/catalog/books/{id}/reviews", sapiensId), patron))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test @Order(9)
    void ratingRemovalRecomputesAggregatesForPublicPage() throws Exception {
        // public book page shows the denormalized aggregates (patron's 5★ from test 1)
        mvc.perform(get("/api/v1/public/libraries/{slug}/books/{bookSlug}", ACCRA_SLUG, sapiensSlug))
                .andExpect(jsonPath("$.ratingAvg").value(5.0))
                .andExpect(jsonPath("$.ratingCount").value(1));
        mvc.perform(api(delete("/api/v1/tenant/catalog/books/{id}/rating", sapiensId), patron))
                .andExpect(jsonPath("$.myStars").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.ratingAvg").value(0.0))
                .andExpect(jsonPath("$.ratingCount").value(0));
    }

    // ---------- media ----------

    @Test @Order(10)
    void coverUploadSniffsContentAndAttachesToBook() throws Exception {
        String tfaId = bookId("Things Fall Apart");
        MockMultipartFile bad = new MockMultipartFile("file", "evil.png", "image/png",
                "not actually an image".getBytes());
        mvc.perform(apiFile(multipart("/api/v1/tenant/catalog/books/{id}/cover", tfaId).file(bad), admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        // patrons cannot upload covers
        mvc.perform(apiFile(multipart("/api/v1/tenant/catalog/books/{id}/cover", tfaId)
                        .file(new MockMultipartFile("file", "c.png", "image/png", pngBytes(500, 700))), patron))
                .andExpect(status().isForbidden());

        MvcResult uploaded = mvc.perform(apiFile(multipart("/api/v1/tenant/catalog/books/{id}/cover", tfaId)
                        .file(new MockMultipartFile("file", "cover.png", "image/png", pngBytes(500, 700))), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("BOOK_COVER"))
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.width").value(500))
                .andReturn();
        coverMediaId = TestApi.readJson(uploaded).path("id").asText();

        // the book now carries the cover reference
        mvc.perform(api(get("/api/v1/tenant/catalog/books/{id}", tfaId), patron))
                .andExpect(jsonPath("$.coverRef").value(coverMediaId));
        // any member can fetch full + thumb; the thumb is genuinely smaller
        byte[] full = mvc.perform(api(get("/api/v1/tenant/media/{id}", coverMediaId), patron))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();
        byte[] thumb = mvc.perform(api(get("/api/v1/tenant/media/{id}?variant=thumb", coverMediaId), reader2))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(full.length).isGreaterThan(0);
        assertThat(thumb.length).isGreaterThan(0);
        assertThat(thumb.length).isLessThan(full.length);
    }

    @Test @Order(11)
    void coversArePublicButAvatarsArePrivate() throws Exception {
        // book cover: served on the public plane without auth
        mvc.perform(get("/api/v1/public/media/{id}", coverMediaId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));

        MvcResult avatar = mvc.perform(apiFile(multipart("/api/v1/tenant/profile/avatar")
                        .file(new MockMultipartFile("file", "me.png", "image/png", pngBytes(200, 200))), patron))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("USER_AVATAR"))
                .andReturn();
        String avatarId = TestApi.readJson(avatar).path("id").asText();
        mvc.perform(api(get("/api/v1/tenant/media/{id}", avatarId), reader2))
                .andExpect(status().isOk());                       // same-tenant members may see avatars
        mvc.perform(get("/api/v1/public/media/{id}", avatarId))
                .andExpect(status().isNotFound());                 // ...but NEVER the public plane
        mvc.perform(api(delete("/api/v1/tenant/profile/avatar"), patron))
                .andExpect(status().isOk());
        mvc.perform(api(get("/api/v1/tenant/media/{id}", avatarId), patron))
                .andExpect(status().isNotFound());                 // soft-deleted reference
    }

    // ---------- plan-driven storage quota ----------

    @Test @Order(12)
    void storageQuotaIsEnforcedFromPlanData() {
        String accraId = libraries.findByUid(ACCRA).orElseThrow().getId();
        // STARTER plan allows 5 GB (subscription_plans.limits JSON — not code)
        long sixGb = 6L * 1024 * 1024 * 1024;
        Throwable thrown = catchThrowable(
                () -> entitlements.requireStorageCapacity(accraId, () -> sixGb, 1));
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).code()).isEqualTo("PLAN_LIMIT_EXCEEDED");
        // small uploads pass the guard
        entitlements.requireStorageCapacity(accraId, () -> 1024, 2048);
    }

    // ---------- helpers ----------

    private MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", TestApi.bearer(token))
                .header("X-Library-Id", ACCRA)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private MockHttpServletRequestBuilder apiFile(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", TestApi.bearer(token)).header("X-Library-Id", ACCRA);
    }

    private String bookId(String query) throws Exception {
        JsonNode search = TestApi.readJson(mvc.perform(
                api(get("/api/v1/tenant/catalog/books?q=" + query.replace(" ", " ")), patron))
                .andExpect(status().isOk()).andReturn());
        return search.path("content").get(0).path("id").asText();
    }

    static byte[] pngBytes(int w, int h) throws Exception {
        return TestApi.pngBytes(w, h);
    }
}
