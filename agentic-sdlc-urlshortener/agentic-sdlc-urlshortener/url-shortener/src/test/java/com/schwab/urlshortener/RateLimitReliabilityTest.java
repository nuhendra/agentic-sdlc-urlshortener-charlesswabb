package com.schwab.urlshortener;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Reliability: the rate limiter sheds load once a client exceeds its window, returning HTTP 429
 * rather than letting a burst overwhelm the service.
 *
 * <p>Uses {@link TestPropertySource} to set a tiny limit, which also gives this test its own
 * application context (isolated rate-limit window) so it cannot interfere with other tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
@TestPropertySource(properties = {
        "shortener.ratelimit.max-per-window=5",
        "shortener.ratelimit.window-seconds=60"
})
class RateLimitReliabilityTest {

    @Autowired
    MockMvc mvc;

    @Test
    void burstBeyondTheLimitIsThrottledWith429() throws Exception {
        int ok = 0;
        int throttled = 0;
        for (int i = 0; i < 12; i++) {
            int status = mvc.perform(post("/api/v1/urls")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"https://schwab.com/burst/" + i + "\"}"))
                    .andReturn().getResponse().getStatus();
            if (status == 201) {
                ok++;
            } else if (status == 429) {
                throttled++;
            }
        }
        assertTrue(ok > 0, "some requests within the limit should succeed");
        assertTrue(throttled > 0, "requests beyond the limit should be throttled with 429");
    }
}
