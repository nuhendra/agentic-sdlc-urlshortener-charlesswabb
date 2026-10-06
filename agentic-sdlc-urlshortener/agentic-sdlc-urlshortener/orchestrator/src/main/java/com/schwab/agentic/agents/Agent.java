package com.schwab.agentic.agents;

import com.schwab.agentic.domain.Artifact;
import com.schwab.agentic.domain.Decision;
import com.schwab.agentic.domain.Enums.Stage;
import com.schwab.agentic.domain.Requirement;
import com.schwab.agentic.domain.RunContext;

import java.util.List;

/**
 * A stage agent: given the requirement and upstream context, it produces the artifact(s)
 * for one SDLC stage by invoking an LLM and parsing the result.
 *
 * <p>Agents are deliberately thin and stateless. All governance (gates, retries, rollback,
 * approval, policy, metrics, audit) lives in the engine — agents only do stage work. This is
 * what keeps agent autonomy "controlled": an agent can propose, but it cannot bypass a gate.
 */
public interface Agent {

    /** @return the SDLC stage this agent handles */
    Stage stage();

    /**
     * Produces this stage's artifact for the given context.
     *
     * @param context the requirement, upstream artifacts, attempt number, and failure feedback
     * @return the produced artifact, any decisions, and token usage
     */
    AgentResult execute(AgentContext context);

    /**
     * @param requirement   the (normalized) requirement
     * @param context       the shared blackboard for reading upstream artifacts
     * @param attempt       1-based attempt number (drives self-correction prompts)
     * @param failureReason why the previous attempt failed, or null on the first attempt
     * @param faultMode     fault-injection switch for the governance demo, or null
     */
    record AgentContext(
            Requirement requirement,
            RunContext context,
            int attempt,
            String failureReason,
            String faultMode) {
    }

    record AgentResult(Artifact artifact, List<Decision> decisions, long tokensUsed, String rawText) {
        public AgentResult {
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }
    }
}
