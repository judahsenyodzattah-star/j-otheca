package com.jotheca.permissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.jotheca.seed.DevDataSeeder;
import com.jotheca.support.TestApi;
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
 * Audit-P4·E19 per-staff permission overrides, in the seeded <b>Pentecost</b> tenant
 * (LIB-PU2026): libadmin = library admin, staff = librarian Kwame Osei. This suite runs
 * before the tenancy suite alphabetically, so it must leave <b>zero override rows</b>
 * behind ({@link #cleanup()}) and must never touch the seeded patron's PENDING membership
 * at Pentecost (all joins here use freshly-registered users).
 */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PermissionsFlowTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    String admin;    // libadmin@ — Pentecost LIBRARY_ADMIN (implicitly holds everything)
    String staff;    // staff@ — Pentecost LIBRARIAN (default pack ± overrides)
    String patron;   // patron@ — Accra member, PENDING at Pentecost (never approved here)
    String staffMembershipId;
    String adminMembershipId;

    private MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder b, String token) {
        return api(b, token, DevDataSeeder.PENTECOST_UID);
    }

    private MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder b, String token, String uid) {
        return b.header("Authorization", TestApi.bearer(token))
                .header("X-Library-Id", uid)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String membershipIdOf(String token, String name) throws Exception {
        MvcResult r = mvc.perform(api(get("/api/v1/tenant/members?status=APPROVED&size=100"), token))
                .andExpect(status().isOk()).andReturn();
        for (JsonNode m : TestApi.readJson(r).get("content")) {
            if (name.equals(m.get("fullName").asText())) return m.get("id").asText();
        }
        throw new IllegalStateException("membership not found for " + name);
    }

    private void setOverride(String token, String membershipId, String perm, String grantedJson, int expect)
            throws Exception {
        mvc.perform(api(put("/api/v1/tenant/permissions/staff/" + membershipId), token)
                        .content("{\"overrides\":[{\"perm\":\"" + perm + "\",\"granted\":" + grantedJson + "}]}"))
                .andExpect(status().is(expect));
    }

    @BeforeAll
    void setup() throws Exception {
        admin = TestApi.login(mvc, "libadmin@jotheca.app", "Library12345!");
        staff = TestApi.login(mvc, "staff@jotheca.app", "Library12345!");
        patron = TestApi.login(mvc, "patron@jotheca.app", "Patron12345!");
        staffMembershipId = membershipIdOf(admin, "Kwame Osei");
        adminMembershipId = membershipIdOf(admin, "Ama Mensah");
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM staff_permissions");
    }

    // ---------- catalog & self-view ----------

    @Test
    @Order(1)
    void catalogIsStaffReadableAndPatronsAreRejected() throws Exception {
        mvc.perform(api(get("/api/v1/tenant/permissions/catalog"), patron))
                .andExpect(status().isForbidden());
        mvc.perform(api(get("/api/v1/tenant/permissions/catalog"), staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10));
        mvc.perform(api(get("/api/v1/tenant/permissions/catalog"), admin))
                .andExpect(status().isOk());
    }

    @Test
    @Order(2)
    void meReportsDefaultsForLibrarianAndEverythingForAdmin() throws Exception {
        MvcResult staffMe = mvc.perform(api(get("/api/v1/tenant/permissions/me"), staff))
                .andExpect(status().isOk()).andReturn();
        JsonNode eff = TestApi.readJson(staffMe).get("effective");
        assertThat(eff.toString()).contains("CIRCULATION_OPS", "ISSUES_HANDLE");
        assertThat(eff.toString()).doesNotContain("CATALOG_MANAGE", "BILLING_MANAGE", "MEMBERS_MANAGE");
        MvcResult adminMe = mvc.perform(api(get("/api/v1/tenant/permissions/me"), admin))
                .andExpect(status().isOk()).andReturn();
        assertThat(TestApi.readJson(adminMe).get("effective").size()).isEqualTo(10);
    }

    // ---------- grant elevates a librarian above the default pack ----------

    @Test
    @Order(3)
    void grantCatalogManageLetsStaffCreateBookAndResetRestoresDenial() throws Exception {
        String body = "{\"title\":\"Permissions Probe Book\",\"author\":\"Audit Bot\"}";
        mvc.perform(api(post("/api/v1/tenant/catalog/books"), staff).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_LIBRARY_ROLE"));

        setOverride(admin, staffMembershipId, "CATALOG_MANAGE", "true", 200);
        MvcResult created = mvc.perform(api(post("/api/v1/tenant/catalog/books"), staff).content(body))
                .andExpect(status().isCreated()).andReturn();
        String bookId = TestApi.readJson(created).get("id").asText();

        setOverride(admin, staffMembershipId, "CATALOG_MANAGE", "null", 200); // back to default
        mvc.perform(api(post("/api/v1/tenant/catalog/books"), staff)
                        .content("{\"title\":\"Denied Book\",\"author\":\"Audit Bot\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_LIBRARY_ROLE"));

        // tidy: delete the probe book as admin so catalog counts elsewhere are untouched
        mvc.perform(api(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/tenant/catalog/books/" + bookId), admin))
                .andExpect(status().isNoContent());
    }

    // ---------- revoke takes a librarian below the default pack ----------

    @Test
    @Order(4)
    void revokeCirculationOpsBlocksDeskEndpointUntilReset() throws Exception {
        mvc.perform(api(get("/api/v1/tenant/circulation/reservations/ready"), staff))
                .andExpect(status().isOk());
        setOverride(admin, staffMembershipId, "CIRCULATION_OPS", "false", 200);
        mvc.perform(api(get("/api/v1/tenant/circulation/reservations/ready"), staff))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_LIBRARY_ROLE"));
        setOverride(admin, staffMembershipId, "CIRCULATION_OPS", "null", 200);
        mvc.perform(api(get("/api/v1/tenant/circulation/reservations/ready"), staff))
                .andExpect(status().isOk());
    }

    // ---------- non-default permissions stay closed until granted ----------

    @Test
    @Order(5)
    void finesWaiveIsDeniedByDefaultAndGrantableOnDemand() throws Exception {
        mvc.perform(api(post("/api/v1/tenant/circulation/fines/some-id/waive"), staff)
                        .content("{\"reason\":\"probe\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_LIBRARY_ROLE"));
        setOverride(admin, staffMembershipId, "FINES_WAIVE", "true", 200); // granted for real
        MvcResult me = mvc.perform(api(get("/api/v1/tenant/permissions/me"), staff))
                .andExpect(status().isOk()).andReturn();
        assertThat(TestApi.readJson(me).get("effective").toString()).contains("FINES_WAIVE");
        setOverride(admin, staffMembershipId, "FINES_WAIVE", "null", 200);
    }

    // ---------- members management as a granted permission ----------

    @Test
    @Order(6)
    void grantMembersManageLetsStaffApproveFreshJoinOnlyWhileGranted() throws Exception {
        long stamp = System.nanoTime();
        String email = "perm-member-" + stamp + "@jotheca.dev";
        TestApi.register(mvc, email, "MemberPass12345", "Perm Probe Member");
        String member = TestApi.login(mvc, email, "MemberPass12345");
        mvc.perform(post("/api/v1/libraries/pentecost/join")
                        .header("Authorization", TestApi.bearer(member))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        // default librarian cannot approve
        MvcResult listing = mvc
                .perform(api(get("/api/v1/tenant/members?status=PENDING&size=100"), admin))
                .andExpect(status().isOk()).andReturn();
        String pendingId = null;
        for (JsonNode m : TestApi.readJson(listing).get("content")) {
            if ("Perm Probe Member".equals(m.get("fullName").asText())) pendingId = m.get("id").asText();
        }
        assertThat(pendingId).isNotNull();
        mvc.perform(api(post("/api/v1/tenant/members/" + pendingId + "/approve"), staff))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_LIBRARY_ROLE"));

        setOverride(admin, staffMembershipId, "MEMBERS_MANAGE", "true", 200);
        mvc.perform(api(post("/api/v1/tenant/members/" + pendingId + "/approve"), staff))
                .andExpect(status().isOk());
        setOverride(admin, staffMembershipId, "MEMBERS_MANAGE", "null", 200);

        // approved member stays; grant is gone again (a second pending approval would 403)
        mvc.perform(api(post("/api/v1/tenant/members/" + pendingId + "/approve"), staff))
                .andExpect(status().isForbidden());
    }

    // ---------- invariants ----------

    @Test
    @Order(7)
    void adminPermissionsAreImmutable() throws Exception {
        mvc.perform(api(put("/api/v1/tenant/permissions/staff/" + adminMembershipId), admin)
                        .content("{\"overrides\":[{\"perm\":\"CATALOG_MANAGE\",\"granted\":false}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ADMIN_PERMS_IMMUTABLE"));
    }

    @Test
    @Order(8)
    void crossTenantOverrideIsInvisiblyNotFound() throws Exception {
        String accra = TestApi.login(mvc, "accraadmin@jotheca.app", "Library12345!");
        // Accra admin targets the Pentecost staff membership THROUGH the Accra header → 404
        mvc.perform(api(put("/api/v1/tenant/permissions/staff/" + staffMembershipId), accra,
                        DevDataSeeder.ACCRA_UID)
                        .content("{\"overrides\":[{\"perm\":\"CATALOG_MANAGE\",\"granted\":true}]}"))
                .andExpect(status().isNotFound());
        // and no override row leaked in
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM staff_permissions WHERE membership_id = ?",
                Integer.class, staffMembershipId);
        assertThat(rows).isZero();
    }
}
