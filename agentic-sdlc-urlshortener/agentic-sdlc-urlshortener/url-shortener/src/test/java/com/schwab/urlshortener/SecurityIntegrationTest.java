package com.schwab.urlshortener;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end JWT security: an unauthenticated write is rejected, the agent service account can
 * obtain a token and use it, and bad credentials are refused. Exercises the real
 * {@code AuthController}, {@code JwtService}, {@code JwtAuthenticationFilter} and
 * {@code SecurityConfig} together (no mocked security).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void unauthenticatedCreateIsRejectedWith401() throws Exception {
        mvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://schwab.com\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void badCredentialsAreRejectedWith401() throws Exception {
        mvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"agent\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentCanObtainTokenAndCreateShortUrl() throws Exception {
        // 1) Obtain a token as the agent service account.
        String tokenJson = mvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"agent\",\"password\":\"agent123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.roles[0]").value("ROLE_AGENT"))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(tokenJson).get("token").asText();

        // 2) Use the bearer token to create a short URL.
        mvc.perform(post("/api/v1/urls")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://schwab.com/secured\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").isNotEmpty());
    }
}
