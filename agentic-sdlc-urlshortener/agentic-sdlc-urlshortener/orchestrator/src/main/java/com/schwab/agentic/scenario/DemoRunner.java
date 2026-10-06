package com.schwab.agentic.scenario;

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
import com.schwab.agentic.metrics.RunMetrics;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * A runnable acceptance demo of the orchestration engine. It exercises the three assignment
 * scenarios plus governance controls (rollback, safe-stop, DAG validation) end-to-end with the
 * hermetic {@link DeterministicLlmClient}, printing the audit trail and reliability metrics and
 * asserting the governance invariants. Exits non-zero if any invariant fails.
 *
 * <p>Run with: {@code java -cp out com.schwab.agentic.scenario.DemoRunner}
 */
public final class DemoRunner {

    private static int failures = 0;

    /**
     * Runs the full governance acceptance demo and exits non-zero if any invariant fails.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        LlmClient llm = new DeterministicLlmClient();
        AuditLog audit = new AuditLog();
        MetricsRegistry metrics = new MetricsRegistry();
        OrchestrationEngine engine = new OrchestrationEngine(agents(llm),
                com.schwab.agentic.policy.PolicyEngine.withDefaults(), audit, metrics);

        banner("SCENARIO 1 — GREENFIELD: add custom alias support");
        Run g = ScenarioLibrary.greenfield();
        RunMetrics gm = engine.execute(g, RunConfig.defaults());
        print(audit, g, gm);
        check(g.state() == RunState.COMPLETED, "greenfield completes");
        check(stage(g, Stage.RELEASE_READINESS) == StageState.SUCCEEDED, "release stage succeeded");
        check(gm.approvals() >= 2, "greenfield hit >=2 approval checkpoints (impl + release)");
        check(gm.retries() == 0, "greenfield needed no retries");

        banner("SCENARIO 2 — BROWNFIELD: harden redirect + rate-limit resolve");
        Run b = ScenarioLibrary.brownfield();
        RunMetrics bm = engine.execute(b, RunConfig.defaults());
        print(audit, b, bm);
        check(b.state() == RunState.COMPLETED, "brownfield completes");
        check(bm.retries() >= 1, "brownfield implementation self-corrected via a retry");
        check(stage(b, Stage.IMPLEMENTATION) == StageState.SUCCEEDED, "implementation recovered");

        banner("SCENARIO 3 — AMBIGUOUS: 'make it more reliable'");
        Run a = ScenarioLibrary.ambiguous();
        RunMetrics am = engine.execute(a, RunConfig.defaults());
        print(audit, a, am);
        check(a.state() == RunState.COMPLETED, "ambiguous completes after clarification checkpoint");
        check(am.approvals() >= 1, "ambiguity forced a human clarification checkpoint");
        // Dynamic re-planning: the requirement is clarified further; downstream must re-derive.
        System.out.println(">> Requirement clarified post-hoc; re-planning from REQUIREMENTS...");
        engine.replan(a, Stage.REQUIREMENTS);
        RunMetrics am2 = engine.execute(a, RunConfig.defaults());
        print(audit, a, am2);
        check(am2.replans() == 1, "re-plan recorded");
        check(a.state() == RunState.COMPLETED, "run re-completes after re-plan");

        banner("GOVERNANCE A — policy BLOCKER triggers rollback (fault-injected secret leak)");
        Run f = ScenarioLibrary.governanceFault();
        RunMetrics fm = engine.execute(f, RunConfig.defaults());
        print(audit, f, fm);
        check(f.state() == RunState.ROLLED_BACK, "secret-leak run rolls back");
        check(fm.rollbacks() > 0, "rollback compensated prior stages");
        check(stage(f, Stage.IMPLEMENTATION) == StageState.FAILED, "implementation failed");
        check(audit.eventsForRun(f.id()).stream()
                        .anyMatch(e -> e.type() == AuditLog.Type.POLICY_GATE),
                "policy gate blocked the secret leak");

        banner("GOVERNANCE B — operator safe-stop before IMPLEMENTATION");
        Run s = ScenarioLibrary.greenfield();
        RunMetrics sm = engine.execute(s, RunConfig.defaults().withStopBefore(Stage.IMPLEMENTATION));
        print(audit, s, sm);
        check(s.state() == RunState.STOPPED, "safe-stop halts the run");
        check(stage(s, Stage.IMPLEMENTATION) != StageState.SUCCEEDED, "implementation never ran");

        banner("GOVERNANCE C — approval rejected triggers rollback");
        Run r = ScenarioLibrary.greenfield();
        RunConfig reject = RunConfig.defaults()
                .withApproval(ApprovalHandler.policy(Map.of(Stage.RELEASE_READINESS, "not approved for release")));
        RunMetrics rm = engine.execute(r, reject);
        print(audit, r, rm);
        check(r.state() == RunState.ROLLED_BACK, "rejected release rolls the run back");

        banner("GOVERNANCE D — cyclic graph rejected at entry");
        checkThrows(() -> {
            DependencyGraph cyclic = new DependencyGraph()
                    .addNode(new StageNode(Stage.DESIGN, Set.of(Stage.IMPLEMENTATION), RiskLevel.LOW, false, 1))
                    .addNode(new StageNode(Stage.IMPLEMENTATION, Set.of(Stage.DESIGN), RiskLevel.LOW, false, 1));
            cyclic.validateAcyclic();
        }, "cyclic dependency graph is rejected");

        banner("FLEET RELIABILITY METRICS");
        System.out.println(metrics.render());

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL GOVERNANCE INVARIANTS HELD ✓");
        } else {
            System.out.println(failures + " INVARIANT(S) FAILED ✗");
            System.exit(1);
        }
    }

    /**
     * Wires one agent per stage onto the given LLM backend.
     *
     * @param llm the LLM backend
     * @return the stage-to-agent map
     */
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

    /**
     * @param run   the run
     * @param stage the stage to inspect
     * @return the current state of that stage's node
     */
    private static StageState stage(Run run, Stage stage) {
        return run.graph().node(stage).state();
    }

    /**
     * Prints a run's audit trail, metrics, and final state.
     *
     * @param audit the audit log
     * @param run   the run
     * @param m     the run's metrics
     */
    private static void print(AuditLog audit, Run run, RunMetrics m) {
        System.out.println("-- audit trail (" + run.id() + ") --");
        System.out.print(audit.render(run.id()));
        System.out.println("-- metrics --");
        System.out.println(m.render());
        System.out.println("-- final run state: " + run.state() + " --\n");
    }

    /**
     * Prints a section banner.
     *
     * @param title the section title
     */
    private static void banner(String title) {
        System.out.println("\n============================================================");
        System.out.println(title);
        System.out.println("============================================================");
    }

    /**
     * Asserts an invariant, printing a pass/fail line and incrementing the failure count on fail.
     *
     * @param condition the invariant that should hold
     * @param what      a description of the invariant
     */
    private static void check(boolean condition, String what) {
        if (condition) {
            System.out.println("  ✓ " + what);
        } else {
            System.out.println("  ✗ FAILED: " + what);
            failures++;
        }
    }

    /**
     * Asserts that an action throws a {@link RuntimeException}.
     *
     * @param action the action expected to throw
     * @param what   a description of the invariant
     */
    private static void checkThrows(Runnable action, String what) {
        try {
            action.run();
            System.out.println("  ✗ FAILED (no exception): " + what);
            failures++;
        } catch (RuntimeException expected) {
            System.out.println("  ✓ " + what);
        }
    }
}
