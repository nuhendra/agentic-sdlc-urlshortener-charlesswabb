package com.schwab.agentic;

import com.schwab.agentic.agents.Agent;
import com.schwab.agentic.agents.SdlcAgents;
import com.schwab.agentic.audit.AuditLog;
import com.schwab.agentic.domain.DependencyGraph;
import com.schwab.agentic.domain.Enums.RiskLevel;
import com.schwab.agentic.domain.Enums.RunState;
import com.schwab.agentic.domain.Enums.Stage;
import com.schwab.agentic.domain.Enums.StageState;
import com.schwab.agentic.domain.Run;
import com.schwab.agentic.domain.StageNode;
import com.schwab.agentic.engine.OrchestrationEngine;
import com.schwab.agentic.engine.RunConfig;
import com.schwab.agentic.gates.ApprovalHandler;
import com.schwab.agentic.llm.DeterministicLlmClient;
import com.schwab.agentic.llm.LlmClient;
import com.schwab.agentic.metrics.MetricsRegistry;
import com.schwab.agentic.policy.PolicyEngine;
import com.schwab.agentic.scenario.ScenarioLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Governance invariants for the orchestration engine, exercised with the hermetic
 * deterministic LLM backend (no network). These mirror the acceptance checks in DemoRunner.
 */
class EngineGovernanceTest {

    private OrchestrationEngine engine;
    private AuditLog audit;
    private MetricsRegistry metrics;

    @BeforeEach
    void setUp() {
        LlmClient llm = new DeterministicLlmClient();
        audit = new AuditLog();
        metrics = new MetricsRegistry();
        engine = new OrchestrationEngine(agents(llm), PolicyEngine.withDefaults(), audit, metrics);
    }

    @Test
    void greenfieldCompletesWithApprovalCheckpoints() {
        Run run = ScenarioLibrary.greenfield();
        var m = engine.execute(run, RunConfig.defaults());
        assertEquals(RunState.COMPLETED, run.state());
        assertEquals(StageState.SUCCEEDED, run.graph().node(Stage.RELEASE_READINESS).state());
        assertTrue(m.approvals() >= 2, "implementation and release are approval checkpoints");
        assertEquals(0, m.retries());
    }

    @Test
    void brownfieldSelfCorrectsViaBoundedRetry() {
        Run run = ScenarioLibrary.brownfield();
        var m = engine.execute(run, RunConfig.defaults());
        assertEquals(RunState.COMPLETED, run.state());
        assertTrue(m.retries() >= 1, "implementation fails once then recovers");
        assertEquals(StageState.SUCCEEDED, run.graph().node(Stage.IMPLEMENTATION).state());
    }

    @Test
    void ambiguousForcesCheckpointThenReplans() {
        Run run = ScenarioLibrary.ambiguous();
        var m = engine.execute(run, RunConfig.defaults());
        assertEquals(RunState.COMPLETED, run.state());
        assertTrue(m.approvals() >= 1);

        engine.replan(run, Stage.REQUIREMENTS);
        var m2 = engine.execute(run, RunConfig.defaults());
        assertEquals(1, m2.replans());
        assertEquals(RunState.COMPLETED, run.state());
    }

    @Test
    void policyBlockerTriggersRollback() {
        Run run = ScenarioLibrary.governanceFault();
        var m = engine.execute(run, RunConfig.defaults());
        assertEquals(RunState.ROLLED_BACK, run.state());
        assertTrue(m.rollbacks() > 0);
        assertTrue(audit.eventsForRun(run.id()).stream()
                .anyMatch(e -> e.type() == AuditLog.Type.POLICY_GATE));
    }

    @Test
    void operatorSafeStopHaltsRun() {
        Run run = ScenarioLibrary.greenfield();
        engine.execute(run, RunConfig.defaults().withStopBefore(Stage.IMPLEMENTATION));
        assertEquals(RunState.STOPPED, run.state());
        assertTrue(run.graph().node(Stage.IMPLEMENTATION).state() != StageState.SUCCEEDED);
    }

    @Test
    void rejectedApprovalRollsBack() {
        Run run = ScenarioLibrary.greenfield();
        var cfg = RunConfig.defaults().withApproval(
                ApprovalHandler.policy(Map.of(Stage.RELEASE_READINESS, "no-go")));
        engine.execute(run, cfg);
        assertEquals(RunState.ROLLED_BACK, run.state());
    }

    @Test
    void cyclicGraphRejected() {
        DependencyGraph cyclic = new DependencyGraph()
                .addNode(new StageNode(Stage.DESIGN, Set.of(Stage.IMPLEMENTATION), RiskLevel.LOW, false, 1))
                .addNode(new StageNode(Stage.IMPLEMENTATION, Set.of(Stage.DESIGN), RiskLevel.LOW, false, 1));
        assertThrows(IllegalStateException.class, cyclic::validateAcyclic);
    }

    private static Map<Stage, Agent> agents(LlmClient llm) {
        Map<Stage, Agent> map = new EnumMap<>(Stage.class);
        map.put(Stage.REQUIREMENTS, new SdlcAgents.RequirementsAnalystAgent(llm));
        map.put(Stage.DESIGN, new SdlcAgents.ArchitectAgent(llm));
        map.put(Stage.IMPLEMENTATION, new SdlcAgents.ImplementerAgent(llm));
        map.put(Stage.TESTING, new SdlcAgents.TesterAgent(llm));
        map.put(Stage.DOCUMENTATION, new SdlcAgents.DocWriterAgent(llm));
        map.put(Stage.RELEASE_READINESS, new SdlcAgents.ReleaseGateAgent(llm));
        return map;
    }
}
