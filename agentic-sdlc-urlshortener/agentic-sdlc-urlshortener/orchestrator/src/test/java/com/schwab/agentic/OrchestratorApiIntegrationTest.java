package com.schwab.agentic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration + end-to-end + authorization coverage for the orchestrator REST API, driven
 * through the full Spring context (security + controller + engine with the deterministic LLM).
 *
 * <p>It also asserts the governance boundary at the HTTP layer: an agent may start and observe
 * runs but may not grant approvals (ADMIN-only).
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrchestratorApiIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void unauthenticatedStartIsRejectedWith401() throws Exception {
        mvc.perform(post("/api/v1/runs/greenfield")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void plainUserCannotStartRun() throws Exception {
        mvc.perform(post("/api/v1/runs/greenfield")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "agent", roles = "AGENT")
    void agentCanStartDriveAndObserveRun() throws Exception {
        // Start a run (202 Accepted) and capture its id.
        String body = mvc.perform(post("/api/v1/runs/greenfield"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String runId = objectMapper.readTree(body).get("runId").asText();

        // Poll status until the run completes (bounded wait; the deterministic run is fast).
        String state = pollForTerminalState(runId);
        assertEquals("COMPLETED", state, "greenfield run should complete");

        // Observe: audit trail and metrics are readable by the agent.
        mvc.perform(get("/api/v1/runs/" + runId + "/audit")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/runs/" + runId + "/metrics")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "agent", roles = "AGENT")
    void agentCannotGrantApprovals() throws Exception {
        // Governance boundary: approvals are ADMIN-only, so an agent is forbidden regardless of id.
        mvc.perform(post("/api/v1/runs/any-run/approvals/RELEASE_READINESS")
                        .param("decision", "approve"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "ops", roles = "ADMIN")
    void adminIsAuthorizedForApprovalEndpoint() throws Exception {
        // ADMIN passes authorization; with nothing pending, the controller reports resolved=false.
        mvc.perform(post("/api/v1/runs/any-run/approvals/RELEASE_READINESS")
                        .param("decision", "approve"))
                .andExpect(status().isOk());
    }

    private String pollForTerminalState(String runId) throws Exception {
        for (int i = 0; i < 100; i++) {
            String body = mvc.perform(get("/api/v1/runs/" + runId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String state = objectMapper.readTree(body).get("state").asText();
            if (!state.equals("RUNNING") && !state.equals("CREATED")) {
                return state;
            }
            Thread.sleep(50);
        }
        fail("run " + runId + " did not reach a terminal state in time");
        return null; // unreachable
    }
}
