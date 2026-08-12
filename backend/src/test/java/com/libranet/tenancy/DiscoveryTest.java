package com.libranet.tenancy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Public discovery + invitation previews (spec §12/§14). */
@SpringBootTest(properties = "app.rate-limit-auth-per-minute=10000")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DiscoveryTest {

    @Autowired MockMvc mvc;

    @Test
    void discoveryListsSeededLibrariesAnonymously() throws Exception {
        mvc.perform(get("/api/v1/libraries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[?(@.slug == 'accra-central')]").exists())
                .andExpect(jsonPath("$.content[?(@.slug == 'pentecost')]").exists())
                .andExpect(jsonPath("$.content[?(@.slug == 'ashesi')]").exists());
    }

    @Test
    void discoveryFiltersByCityAndType() throws Exception {
        mvc.perform(get("/api/v1/libraries?city=Berekuso"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].slug").value("ashesi"));
        mvc.perform(get("/api/v1/libraries?type=PUBLIC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].slug").value("accra-central"));
        mvc.perform(get("/api/v1/libraries?search=pentecost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void invitationPreviewShowsLibraryWithoutInternalIds() throws Exception {
        mvc.perform(get("/api/v1/join/ashesi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.librarySlug").value("ashesi"))
                .andExpect(jsonPath("$.libraryName").value("Ashesi University Library"))
                .andExpect(jsonPath("$.membership").value("STUDENT"));
    }

    @Test
    void unknownInvitationIs404() throws Exception {
        mvc.perform(get("/api/v1/join/does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
