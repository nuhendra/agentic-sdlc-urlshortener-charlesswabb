package com.schwab.urlshortener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end slice through the real Spring context and an in-memory H2 database:
 * create -> redirect -> stats, plus the key error paths.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN") // these tests exercise the endpoints as an authenticated admin
class UrlControllerIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void createRedirectAndStatsHappyPath() throws Exception {
        String body = "{\"url\":\"https://schwab.com/retirement/planning\"}";

        String response = mvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").isNotEmpty())
                .andExpect(jsonPath("$.longUrl").value("https://schwab.com/retirement/planning"))
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(response);
        String code = node.get("shortCode").asText();

        // Redirect must be a 302 with a Location header (so analytics keeps counting).
        mvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://schwab.com/retirement/planning"));

        mvc.perform(get("/api/v1/urls/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value(code))
                .andExpect(jsonPath("$.longUrl").value("https://schwab.com/retirement/planning"));
    }

    @Test
    void sameUrlIsDeduplicatedToOneCode() throws Exception {
        String body = "{\"url\":\"https://schwab.com/dedup-check\"}";
        String first = codeOf(mvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
        String second = codeOf(mvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertEquals(first, second);
    }

    @Test
    void invalidSchemeIsRejectedWith400() throws Exception {
        String body = "{\"url\":\"javascript:alert(1)\"}";
        mvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void unknownCodeResolvesTo404() throws Exception {
        mvc.perform(get("/api/v1/urls/doesnotexist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void blankUrlFailsValidation() throws Exception {
        mvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private String codeOf(String json) throws Exception {
        return objectMapper.readTree(json).get("shortCode").asText();
    }
}
