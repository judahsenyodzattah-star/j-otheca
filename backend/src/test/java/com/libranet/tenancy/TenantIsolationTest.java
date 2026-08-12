package com.libranet.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.libranet.seed.DevDataSeeder;
import com.libranet.support.TestApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * THE critical suite (spec §42). It must stay green for every future phase:
 *
 *   A caller from Library A MUST NOT read or mutate Library B — by any header,
 *   any resource id, any invitation, or any role trick.
 *
 * New tenant-scoped endpoints: add a case here before merging.
 */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class TenantIsolationTest {

    @Autowired MockMvc mvc;

    // ---------- layer: tenant resolution ----------

    @Test
    void callerWithoutMembershipCannotEnterAnotherTenant() throws Exception {
        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        // libadmin belongs to Pentecost, NOT to Accra Central — spoofed header must be rejected
        mvc.perform(get("/api/v1/tenant/members")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_MEMBERSHIP"));
    }

    @Test
    void pendingMembershipDoesNotGrantTenantAccess() throws Exception {
        String patron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        // patron is only PENDING at Pentecost — not approved
        mvc.perform(get("/api/v1/tenant/members")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isForbidden());
    }

    @Test
    void patronCannotUseStaffEndpointsInsideOwnTenant() throws Exception {
        String patron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        mvc.perform(get("/api/v1/tenant/members")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_LIBRARY_ROLE"));
    }

    @Test
    void unknownTenantUidIsNotFound() throws Exception {
        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        mvc.perform(get("/api/v1/tenant/members")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", "LIB-ZZZZZZ"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingTenantHeaderFailsClosed() throws Exception {
        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        mvc.perform(get("/api/v1/tenant/members")
                        .header("Authorization", TestApi.bearer(libadmin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedTenantRequestIsRejected() throws Exception {
        mvc.perform(get("/api/v1/tenant/members")
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isUnauthorized());
    }

    // ---------- layer: data filtering ----------

    @Test
    void memberListContainsOnlyOwnTenantRows() throws Exception {
        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        MvcResult result = mvc.perform(get("/api/v1/tenant/members")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = TestApi.readJson(result).path("content");
        assertThat(content).isNotEmpty();
        java.util.List<String> emails = new java.util.ArrayList<>();
        for (JsonNode row : content) {
            emails.add(row.path("email").asText());
        }
        // the Pentecost roster itself is visible…
        assertThat(emails).contains("libadmin@libranet.app", "staff@libranet.app");
        // …and never contains an Accra-only account (isolation, not a fixed-roster assertion:
        // other suites freely create extra Pentecost members over the shared context)
        assertThat(emails).doesNotContain("accraadmin@libranet.app", "admin@libranet.app");
    }

    // ---------- layer: IDOR ----------

    @Test
    void crossTenantMembershipIdAnswers404NotForbidden() throws Exception {
        String patron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        MvcResult mine = mvc.perform(get("/api/v1/me/memberships")
                        .header("Authorization", TestApi.bearer(patron)))
                .andExpect(status().isOk())
                .andReturn();
        String accraMembershipId = null;
        for (JsonNode m : TestApi.readJson(mine)) {
            if (DevDataSeeder.ACCRA_UID.equals(m.path("libraryUid").asText())) {
                accraMembershipId = m.path("membershipId").asText();
            }
        }
        assertThat(accraMembershipId).isNotNull();

        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        // admin of Pentecost attempts to act on an ACCRA membership id → 404 (existence not revealed)
        mvc.perform(post("/api/v1/tenant/members/" + accraMembershipId + "/approve")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isNotFound());
    }

    @Test
    void invitationOfOneLibraryCannotBeUsedAtAnother() throws Exception {
        String email = "inv-" + System.nanoTime() + "@example.com";
        TestApi.register(mvc, email, "Password12345", "Joiner");
        String token = TestApi.login(mvc, email, "Password12345");
        // Ashesi's invitation code must NOT work against Pentecost
        mvc.perform(post("/api/v1/libraries/pentecost/join")
                        .header("Authorization", TestApi.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + DevDataSeeder.ASHESI_INVITE_CODE + "\"}"))
                .andExpect(status().isOk()); // pentecost is APPROVAL_REQUIRED → PENDING, invitation ignored
        // and re-using it where it DOES belong works (sanity that the code itself is valid):
        String email2 = "inv2-" + System.nanoTime() + "@example.com";
        TestApi.register(mvc, email2, "Password12345", "Joiner Two");
        String token2 = TestApi.login(mvc, email2, "Password12345");
        mvc.perform(post("/api/v1/libraries/ashesi/join")
                        .header("Authorization", TestApi.bearer(token2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + DevDataSeeder.ASHESI_INVITE_CODE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.membershipNumber").isNotEmpty());
    }

    @Test
    void invitationOnlyLibraryRejectsJoinWithoutCode() throws Exception {
        String email = "noi-" + System.nanoTime() + "@example.com";
        TestApi.register(mvc, email, "Password12345", "No Invite");
        String token = TestApi.login(mvc, email, "Password12345");
        mvc.perform(post("/api/v1/libraries/ashesi/join")
                        .header("Authorization", TestApi.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVITATION_REQUIRED"));
    }

    // ---------- layer: platform separation ----------

    @Test
    void libraryAdminCannotReachPlatformPlane() throws Exception {
        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        mvc.perform(get("/api/v1/platform/libraries")
                        .header("Authorization", TestApi.bearer(libadmin)))
                .andExpect(status().isForbidden());
    }

    @Test
    void superAdminSeesPlatformButCannotOperateTenantPlaneWithoutMembership() throws Exception {
        String admin = TestApi.login(mvc, "admin@libranet.app", "Admin12345!");
        mvc.perform(get("/api/v1/platform/stats")
                        .header("Authorization", TestApi.bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLibraries").isNumber());
        // spec §8: the platform owner does not run library operations — no membership, no tenant context
        mvc.perform(get("/api/v1/tenant/members")
                        .header("Authorization", TestApi.bearer(admin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isForbidden());
    }

    // ---------- join/approve happy flows inside one tenant ----------

    @Test
    void joinRequestApprovalFlowWithinOneLibrary() throws Exception {
        String email = "appr-" + System.nanoTime() + "@example.com";
        TestApi.register(mvc, email, "Password12345", "Approval Candidate");
        String userToken = TestApi.login(mvc, email, "Password12345");

        mvc.perform(post("/api/v1/libraries/pentecost/join")
                        .header("Authorization", TestApi.bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipType\":\"STUDENT\",\"verificationPayload\":{\"studentId\":\"PU-9911\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        MvcResult pending = mvc.perform(get("/api/v1/tenant/members?status=PENDING")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isOk())
                .andReturn();

        String membershipId = null;
        for (JsonNode row : TestApi.readJson(pending).path("content")) {
            if (email.equals(row.path("email").asText())) {
                membershipId = row.path("id").asText();
            }
        }
        assertThat(membershipId).isNotNull();

        mvc.perform(post("/api/v1/tenant/members/" + membershipId + "/approve")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.membershipNumber").isNotEmpty());

        mvc.perform(get("/api/v1/me/memberships")
                        .header("Authorization", TestApi.bearer(userToken)))
                .andExpect(status().isOk());
    }

    @Test
    void librarianCannotApproveMembers() throws Exception {
        // role separation (spec §5 matrix): member administration is admin-only
        String staff = TestApi.login(mvc, "staff@libranet.app", "Library12345!");
        mvc.perform(post("/api/v1/tenant/members/whatever/approve")
                        .header("Authorization", TestApi.bearer(staff))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_LIBRARY_ROLE"));
    }

    @Test
    void librarianCannotAppointRolesButAdminCanAndCrossTenantIs404() throws Exception {
        // fresh member joins Pentecost and is approved by its admin
        String email = "role-" + System.nanoTime() + "@example.com";
        TestApi.register(mvc, email, "Password12345", "Role Target");
        String userToken = TestApi.login(mvc, email, "Password12345");
        mvc.perform(post("/api/v1/libraries/pentecost/join")
                        .header("Authorization", TestApi.bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                
                .andExpect(status().isOk());

        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        MvcResult pending = mvc.perform(get("/api/v1/tenant/members?status=PENDING")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isOk()).andReturn();
        String membershipId = null;
        for (JsonNode row : TestApi.readJson(pending).path("content")) {
            if (email.equals(row.path("email").asText())) membershipId = row.path("id").asText();
        }
        assertThat(membershipId).isNotNull();
        mvc.perform(post("/api/v1/tenant/members/" + membershipId + "/approve")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isOk());

        // librarian must NOT appoint (admin-only per spec §9)
        String staff = TestApi.login(mvc, "staff@libranet.app", "Library12345!");
        mvc.perform(post("/api/v1/tenant/members/" + membershipId + "/role")
                        .header("Authorization", TestApi.bearer(staff))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"LIBRARIAN\"}"))
                .andExpect(status().isForbidden());

        // admin appoints — happy path within staff capacity
        mvc.perform(post("/api/v1/tenant/members/" + membershipId + "/role")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"LIBRARIAN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("LIBRARIAN"));

        // cross-tenant: admin of Pentecost cannot touch an Accra membership id
        String patron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        MvcResult mine = mvc.perform(get("/api/v1/me/memberships")
                        .header("Authorization", TestApi.bearer(patron)))
                .andExpect(status().isOk()).andReturn();
        String accraMembershipId = null;
        for (JsonNode m : TestApi.readJson(mine)) {
            if (DevDataSeeder.ACCRA_UID.equals(m.path("libraryUid").asText())) {
                accraMembershipId = m.path("membershipId").asText();
            }
        }
        mvc.perform(post("/api/v1/tenant/members/" + accraMembershipId + "/role")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"LIBRARIAN\"}"))
                .andExpect(status().isNotFound());
    }

    // ---------- layer: catalog isolation (Phase 4) ----------

    @Test
    void catalogSearchContainsOnlyOwnTenantBooks() throws Exception {
        String staff = TestApi.login(mvc, "staff@libranet.app", "Library12345!");
        MvcResult result = mvc.perform(get("/api/v1/tenant/catalog/books")
                        .header("Authorization", TestApi.bearer(staff))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = TestApi.readJson(result).path("content");
        assertThat(content).isNotEmpty();
        for (JsonNode book : content) {
            // Pentecost titles only — never an Accra holding
            assertThat(book.path("title").asText()).doesNotContain("Things Fall Apart", "Sapiens", "Homegoing");
        }
    }

    @Test
    void crossTenantBookIdAnswers404() throws Exception {
        String accraBookId = accraBookId();
        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        // Admin of Pentecost requests an Accra book by id inside Pentecost context → 404, existence hidden
        mvc.perform(get("/api/v1/tenant/catalog/books/" + accraBookId)
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isNotFound());
    }

    @Test
    void crossTenantCopyIdAnswers404ForStaff() throws Exception {
        String accraAdmin = TestApi.login(mvc, "accraadmin@libranet.app", "Library12345!");
        MvcResult copies = mvc.perform(get("/api/v1/tenant/catalog/books/" + accraBookId() + "/copies")
                        .header("Authorization", TestApi.bearer(accraAdmin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andReturn();
        String accraCopyId = TestApi.readJson(copies).get(0).path("id").asText();

        String staff = TestApi.login(mvc, "staff@libranet.app", "Library12345!");
        mvc.perform(get("/api/v1/tenant/catalog/copies/" + accraCopyId)
                        .header("Authorization", TestApi.bearer(staff))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isNotFound());
    }

    @Test
    void crossTenantCategoryCannotBeReferencedOrMutated() throws Exception {
        String accraAdmin = TestApi.login(mvc, "accraadmin@libranet.app", "Library12345!");
        MvcResult tree = mvc.perform(get("/api/v1/tenant/catalog/categories")
                        .header("Authorization", TestApi.bearer(accraAdmin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andReturn();
        String accraCategoryId = TestApi.readJson(tree).get(0).path("id").asText();

        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        // mutate by id → 404
        mvc.perform(put("/api/v1/tenant/catalog/categories/" + accraCategoryId)
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hijacked\"}"))
                .andExpect(status().isNotFound());
        // foreign key spoofing: Pentecost book pointing at an Accra category → generic 404 hides it
        mvc.perform(post("/api/v1/tenant/catalog/books")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Spoof\",\"author\":\"Spoof\",\"categoryId\":\""
                                + accraCategoryId + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** Resolves a seeded Accra book id via a member of Accra (the legitimate way to know the id). */
    private String accraBookId() throws Exception {
        String patron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        MvcResult result = mvc.perform(get("/api/v1/tenant/catalog/books?q=Things")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andReturn();
        return TestApi.readJson(result).path("content").get(0).path("id").asText();
    }

    // ---------- layer: circulation isolation (Phase 5) ----------

    @Test
    void crossTenantLoanIdAnswers404OnReturn() throws Exception {
        String accraAdmin = TestApi.login(mvc, "accraadmin@libranet.app", "Library12345!");
        MvcResult loans = mvc.perform(get("/api/v1/tenant/circulation/loans")
                        .header("Authorization", TestApi.bearer(accraAdmin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andReturn();
        String accraLoanId = TestApi.readJson(loans).path("content").get(0).path("id").asText();

        // Pentecost librarian tries to close an ACCRA loan inside Pentecost context → 404
        String staff = TestApi.login(mvc, "staff@libranet.app", "Library12345!");
        mvc.perform(post("/api/v1/tenant/circulation/loans/" + accraLoanId + "/return")
                        .header("Authorization", TestApi.bearer(staff))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isNotFound());
    }

    @Test
    void barcodeScanResolvesOnlyWithinOwnTenant() throws Exception {
        String staff = TestApi.login(mvc, "staff@libranet.app", "Library12345!");
        // AC-000001 exists — but in Accra. Scanning it inside Pentecost context finds nothing.
        mvc.perform(post("/api/v1/tenant/circulation/returns")
                        .header("Authorization", TestApi.bearer(staff))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"barcode\":\"AC-000001\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COPY_NOT_FOUND"));
        // card numbers are tenant-scoped as well: MBR-AC0001 (Accra) is not a Pentecost member
        mvc.perform(get("/api/v1/tenant/circulation/members/lookup?number=MBR-AC0001")
                        .header("Authorization", TestApi.bearer(staff))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isNotFound());
    }

    // ---------- layer: publishing isolation (Phase 6) ----------

    @Test
    void interestAndPurchaseAcrossTenantsAnswer404() throws Exception {
        // a real Pentecost book id, obtained legitimately by Pentecost staff
        String staff = TestApi.login(mvc, "staff@libranet.app", "Library12345!");
        MvcResult search = mvc.perform(get("/api/v1/tenant/catalog/books?q=Database")
                        .header("Authorization", TestApi.bearer(staff))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isOk())
                .andReturn();
        String pentecostBookId = TestApi.readJson(search).path("content").get(0).path("id").asText();

        // an Accra patron pointing at a Pentecost book id inside Accra context → 404, existence hidden
        String patron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        mvc.perform(put("/api/v1/tenant/catalog/books/" + pentecostBookId + "/interest")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/tenant/catalog/books/" + pentecostBookId + "/purchase")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicJoinGrantsInstantMembership() throws Exception {
        String email = "pub-" + System.nanoTime() + "@example.com";
        TestApi.register(mvc, email, "Password12345", "Public Joiner");
        String token = TestApi.login(mvc, email, "Password12345");
        mvc.perform(post("/api/v1/libraries/accra-central/join")
                        .header("Authorization", TestApi.bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.membershipNumber").isNotEmpty());
    }

    // ---------- Phase 7: media, ratings, reviews ----------

    @Test
    void ratingAndReviewAcrossTenantsAnswer404() throws Exception {
        // a real Accra book id, obtained legitimately by Accra staff
        String accraAdmin = TestApi.login(mvc, "accraadmin@libranet.app", "Library12345!");
        MvcResult search = mvc.perform(get("/api/v1/tenant/catalog/books?q=Sapiens")
                        .header("Authorization", TestApi.bearer(accraAdmin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andReturn();
        String accraBookId = TestApi.readJson(search).path("content").get(0).path("id").asText();

        // Pentecost staff pointing at an Accra book id inside Pentecost context → 404, existence hidden
        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        mvc.perform(put("/api/v1/tenant/catalog/books/" + accraBookId + "/rating")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stars\":5}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/tenant/catalog/books/" + accraBookId + "/reviews")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"cross-tenant\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/tenant/catalog/books/" + accraBookId + "/reviews")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isNotFound());
    }

    @Test
    void reviewModerationAcrossTenantsAnswers404() throws Exception {
        // Accra admin writes a review at Accra
        String accraAdmin = TestApi.login(mvc, "accraadmin@libranet.app", "Library12345!");
        MvcResult search = mvc.perform(get("/api/v1/tenant/catalog/books?q=Things Fall Apart")
                        .header("Authorization", TestApi.bearer(accraAdmin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andReturn();
        String bookId = TestApi.readJson(search).path("content").get(0).path("id").asText();
        MvcResult created = mvc.perform(post("/api/v1/tenant/catalog/books/" + bookId + "/reviews")
                        .header("Authorization", TestApi.bearer(accraAdmin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Staff pick!\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String reviewId = TestApi.readJson(created).path("id").asText();

        // Pentecost staff cannot moderate (approve/reject/helpful/report) an Accra review — 404 everywhere
        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        for (String action : new String[] {"/approve", "/reject", "/report", "/reports/dismiss"}) {
            mvc.perform(post("/api/v1/tenant/reviews/" + reviewId + action)
                            .header("Authorization", TestApi.bearer(libadmin))
                            .header("X-Library-Id", DevDataSeeder.PENTECOST_UID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(action.equals("/report") ? "{\"reason\":\"x\"}" : "{}"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(put("/api/v1/tenant/reviews/" + reviewId + "/helpful")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isNotFound());
    }

    @Test
    void sessionsOfOtherAccountsAnswer404() throws Exception {
        // accraadmin learns their own session id legitimately…
        TestApi.login(mvc, "accraadmin@libranet.app", "Library12345!");
        String access = TestApi.readJson(mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"accraadmin@libranet.app\",\"password\":\"Library12345!\"}"))
                .andReturn()).path("accessToken").asText();
        MvcResult list = mvc.perform(get("/api/v1/me/security/sessions")
                        .header("Authorization", TestApi.bearer(access)))
                .andExpect(status().isOk())
                .andReturn();
        String foreignSessionId = TestApi.readJson(list).get(0).path("id").asText();

        // …but another user cannot revoke it — 404, existence hidden
        String patron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        mvc.perform(delete("/api/v1/me/security/sessions/" + foreignSessionId)
                        .header("Authorization", TestApi.bearer(patron)))
                .andExpect(status().isNotFound());
    }

    @Test
    void tenantAuditViewerNeverReturnsOtherTenantsEvents() throws Exception {
        // accraadmin triggered plenty of Accra audit rows by now; from Pentecost the actor filter sees nothing
        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        mvc.perform(get("/api/v1/tenant/audit?actorEmail=accraadmin@libranet.app")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void mediaAcrossTenantsAnswers404() throws Exception {
        // Accra admin uploads an avatar (tiny valid PNG) inside Accra
        String accraAdmin = TestApi.login(mvc, "accraadmin@libranet.app", "Library12345!");
        byte[] png = TestApi.pngBytes(64, 64);
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", png);
        MvcResult uploaded = mvc.perform(multipart("/api/v1/tenant/profile/avatar").file(file)
                        .header("Authorization", TestApi.bearer(accraAdmin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andReturn();
        String mediaId = TestApi.readJson(uploaded).path("id").asText();

        // Pentecost staff cannot read or delete it — 404, existence hidden
        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        mvc.perform(get("/api/v1/tenant/media/" + mediaId)
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/tenant/media/" + mediaId)
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isNotFound());
    }

    // ---------- Phase 9: analytics + patron payment rails ----------

    @Test
    void tenantAnalyticsArePinnedToTheirOwnLibrary() throws Exception {
        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        String accraAdmin = TestApi.login(mvc, "accraadmin@libranet.app", "Library12345!");

        // Pentecost fixture: exactly 3 seeded books; Accra: 8+ (MediaReviewFlowTest adds none there,
        // CatalogFlowTest grows it) — each dashboard reports ONLY its own tenant's numbers.
        mvc.perform(get("/api/v1/tenant/analytics/dashboard")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.books.used").value(3));
        MvcResult accra = mvc.perform(get("/api/v1/tenant/analytics/dashboard")
                        .header("Authorization", TestApi.bearer(accraAdmin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk())
                .andReturn();
        long accraBooks = TestApi.readJson(accra).path("books").path("used").asLong();
        org.assertj.core.api.Assertions.assertThat(accraBooks).isGreaterThanOrEqualTo(8);
        org.assertj.core.api.Assertions.assertThat(accraBooks).isNotEqualTo(3);

        // patrons cannot open the admin dashboard at all
        String patron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        mvc.perform(get("/api/v1/tenant/analytics/dashboard")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantFineOnlineRailsAnswer404() throws Exception {
        // Accra staff assesses a fine inside Accra; Pentecost staff see nothing — on either rail.
        String accraAdmin = TestApi.login(mvc, "accraadmin@libranet.app", "Library12345!");
        MvcResult lookup = mvc.perform(get("/api/v1/tenant/circulation/members/lookup?number=MBR-AC0001")
                        .header("Authorization", TestApi.bearer(accraAdmin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk()).andReturn();
        String membershipId = TestApi.readJson(lookup).path("membershipId").asText();
        MvcResult fineRes = mvc.perform(post("/api/v1/tenant/circulation/fines")
                        .header("Authorization", TestApi.bearer(accraAdmin))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"membershipId\":\"" + membershipId
                                + "\",\"type\":\"MANUAL\",\"amountMinor\":1000,\"note\":\"isolation probe\"}"))
                .andExpect(status().isCreated()).andReturn();
        String fineId = TestApi.readJson(fineRes).path("id").asText();

        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        mvc.perform(post("/api/v1/tenant/circulation/fines/" + fineId + "/pay-online")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/tenant/circulation/fines/" + fineId + "/confirm-online")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void platformAnalyticsNeverLeaksIntoTenantPlane() throws Exception {
        // tenant admin hitting the platform dashboard is refused (SUPER_ADMIN role required);
        // and the platform dashboard itself keeps a disjoint path prefix.
        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        mvc.perform(get("/api/v1/platform/analytics/dashboard")
                        .header("Authorization", TestApi.bearer(libadmin)))
                .andExpect(status().isForbidden());
        String superAdmin = TestApi.login(mvc, "admin@libranet.app", "Admin12345!");
        mvc.perform(get("/api/v1/platform/analytics/dashboard")
                        .header("Authorization", TestApi.bearer(superAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mrr.currency").value("GHS"));
    }

    // ---------- Phase 10: AI surfaces ----------

    @Test
    void semanticSearchNeverScoresForeignEmbeddings() throws Exception {
        // Accra: "Things Fall Apart" is lexically strong for this query (seeded fixture).
        // Pentecost does NOT own that title; its ANN search must stay inside Pentecost's 3 books.
        String patron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");

        MvcResult accra = mvc.perform(get("/api/v1/tenant/ai/search")
                        .param("q", "african novel colonial village")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk()).andReturn();
        JsonNode accraHits = TestApi.readJson(accra).path("hits");
        org.assertj.core.api.Assertions.assertThat(accraHits.toString()).contains("Things Fall Apart");

        MvcResult pentecost = mvc.perform(get("/api/v1/tenant/ai/search")
                        .param("q", "african novel colonial village")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isOk()).andReturn();
        String pentBody = TestApi.readJson(pentecost).path("hits").toString();
        org.assertj.core.api.Assertions.assertThat(pentBody).doesNotContain("Things Fall Apart")
                .doesNotContain("Sapiens");
        // and every returned hit really belongs to Pentecost (id prefix domain check via public catalog)
        for (JsonNode hit : TestApi.readJson(pentecost).path("hits")) {
            mvc.perform(get("/api/v1/tenant/catalog/books/" + hit.path("bookId").asText())
                            .header("Authorization", TestApi.bearer(libadmin))
                            .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                    .andExpect(status().isOk()); // readable only if it truly is Pentecost-owned
            mvc.perform(get("/api/v1/tenant/catalog/books/" + hit.path("bookId").asText())
                            .header("Authorization", TestApi.bearer(patron))
                            .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                    .andExpect(status().isNotFound()); // and invisible from Accra's scope
        }
    }

    @Test
    void recommendationsStayInsideTheCallersTenant() throws Exception {
        // patron has circulation history in Accra; Pentecost recommendations must not echo Accra titles
        String patron = TestApi.login(mvc, "patron@libranet.app", "Patron12345!");
        JsonNode me = TestApi.readJson(mvc.perform(get("/api/v1/tenant/catalog/books")
                        .header("Authorization", TestApi.bearer(patron))
                        .header("X-Library-Id", DevDataSeeder.ACCRA_UID))
                .andExpect(status().isOk()).andReturn());
        java.util.Set<String> accraIds = new java.util.HashSet<>();
        me.path("content").forEach(b -> accraIds.add(b.path("id").asText()));
        org.assertj.core.api.Assertions.assertThat(accraIds).isNotEmpty();

        // patron IS also a (pending→) member of Pentecost per seeds; use libadmin for certainty
        String libadmin = TestApi.login(mvc, "libadmin@libranet.app", "Library12345!");
        JsonNode recs = TestApi.readJson(mvc.perform(get("/api/v1/tenant/ai/recommendations")
                        .header("Authorization", TestApi.bearer(libadmin))
                        .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode rec : recs.path("recommendations")) {
            String id = rec.path("bookId").asText();
            org.assertj.core.api.Assertions.assertThat(accraIds).doesNotContain(id);
            // cross-check via the other tenant's catalog — foreign ids would 404 there…
            mvc.perform(get("/api/v1/tenant/catalog/books/" + id)
                            .header("Authorization", TestApi.bearer(libadmin))
                            .header("X-Library-Id", DevDataSeeder.PENTECOST_UID))
                    .andExpect(status().isOk()); // …but resolve fine in the caller's tenant
        }
    }
}
