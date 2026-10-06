package com.schwab.agentic.config;

import com.schwab.agentic.agents.Agent;
import com.schwab.agentic.agents.SdlcAgents;
import com.schwab.agentic.audit.AuditLog;
import com.schwab.agentic.domain.Enums.Stage;
import com.schwab.agentic.engine.OrchestrationEngine;
import com.schwab.agentic.llm.AnthropicLlmClient;
import com.schwab.agentic.llm.DeterministicLlmClient;
import com.schwab.agentic.llm.LlmClient;
import com.schwab.agentic.metrics.MetricsRegistry;
import com.schwab.agentic.policy.PolicyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;

/**
 * Wires the framework-free engine into Spring beans.
 *
 * <p>The LLM backend is chosen at startup: if {@code ANTHROPIC_API_KEY} is present the real
 * {@link AnthropicLlmClient} is used (the assignment's "real LLM" requirement); otherwise the
 * service falls back to {@link DeterministicLlmClient} so the API still runs and demos offline,
 * logging a clear warning.
 */
@Configuration
public class OrchestratorConfig {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorConfig.class);

    /**
     * Selects the LLM backend at startup: the real Anthropic client when
     * {@code ANTHROPIC_API_KEY} is set, otherwise the hermetic deterministic client.
     *
     * @return the chosen {@link LlmClient} bean
     */
    @Bean
    public LlmClient llmClient() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key != null && !key.isBlank()) {
            log.info("Using real Anthropic LLM backend");
            return AnthropicLlmClient.fromEnv();
        }
        log.warn("ANTHROPIC_API_KEY not set; falling back to DeterministicLlmClient "
                + "(offline, hermetic). Set the key to exercise the real-LLM path.");
        return new DeterministicLlmClient();
    }

    /**
     * Wires one agent per stage onto the selected LLM backend.
     *
     * @param llm the LLM backend bean
     * @return the stage-to-agent map
     */
    @Bean
    public Map<Stage, Agent> agents(LlmClient llm) {
        Map<Stage, Agent> map = new EnumMap<>(Stage.class);
        map.put(Stage.REQUIREMENTS, new SdlcAgents.RequirementsAnalystAgent(llm));
        map.put(Stage.DESIGN, new SdlcAgents.ArchitectAgent(llm));
        map.put(Stage.IMPLEMENTATION, new SdlcAgents.ImplementerAgent(llm));
        map.put(Stage.TESTING, new SdlcAgents.TesterAgent(llm));
        map.put(Stage.DOCUMENTATION, new SdlcAgents.DocWriterAgent(llm));
        map.put(Stage.RELEASE_READINESS, new SdlcAgents.ReleaseGateAgent(llm));
        return map;
    }

    /** @return the policy engine with the default security/change-control rules */
    @Bean
    public PolicyEngine policyEngine() {
        return PolicyEngine.withDefaults();
    }

    /** @return the shared append-only audit log */
    @Bean
    public AuditLog auditLog() {
        return new AuditLog();
    }

    /** @return the shared reliability-metrics registry */
    @Bean
    public MetricsRegistry metricsRegistry() {
        return new MetricsRegistry();
    }

    /**
     * Assembles the orchestration engine from its collaborator beans.
     *
     * @param agents          the stage-to-agent map
     * @param policyEngine    the policy engine
     * @param auditLog        the audit log
     * @param metricsRegistry the metrics registry
     * @return the configured {@link OrchestrationEngine}
     */
    @Bean
    public OrchestrationEngine orchestrationEngine(Map<Stage, Agent> agents,
                                                   PolicyEngine policyEngine,
                                                   AuditLog auditLog,
                                                   MetricsRegistry metricsRegistry) {
        return new OrchestrationEngine(agents, policyEngine, auditLog, metricsRegistry);
    }
}
