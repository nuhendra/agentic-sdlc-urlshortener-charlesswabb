package com.schwab.agentic.engine;

import com.schwab.agentic.agents.Agent;
import com.schwab.agentic.agents.Agent.AgentContext;
import com.schwab.agentic.agents.Agent.AgentResult;
import com.schwab.agentic.audit.AuditLog;
import com.schwab.agentic.audit.AuditLog.Type;
import com.schwab.agentic.domain.Artifact;
import com.schwab.agentic.domain.Decision;
import com.schwab.agentic.domain.Enums.RunState;
import com.schwab.agentic.domain.Enums.Stage;
import com.schwab.agentic.domain.Enums.StageState;
import com.schwab.agentic.domain.Run;
import com.schwab.agentic.domain.StageNode;
import com.schwab.agentic.gates.ApprovalHandler.ApprovalDecision;
import com.schwab.agentic.gates.ApprovalHandler.ApprovalRequest;
import com.schwab.agentic.gates.Gates;
import com.schwab.agentic.gates.Gates.GateResult;
import com.schwab.agentic.metrics.MetricsRegistry;
import com.schwab.agentic.metrics.RunMetrics;
import com.schwab.agentic.policy.PolicyEngine;
import com.schwab.agentic.policy.PolicyEngine.Violation;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The governed orchestration engine.
 *
 * <p>It drives the dependency graph as a non-linear, stateful pipeline rather than a linear
 * chain: each round it computes the ready frontier and executes those stages in parallel
 * (virtual threads) with a join barrier for synchronization. Every stage passes through an
 * entry gate, bounded self-correcting retries, an exit gate, a policy gate, and — for
 * high-impact actions — a human approval checkpoint. Failure that cannot be recovered triggers
 * saga-style rollback; an operator kill-switch or a breached error budget triggers safe-stop.
 * Everything is audited and measured.
 */
public final class OrchestrationEngine {

    /** Outcome of executing a single stage node. */
    private enum NodeOutcome {SUCCEEDED, SKIPPED, FAILED}

    private final Map<Stage, Agent> agents;
    private final PolicyEngine policyEngine;
    private final AuditLog audit;
    private final MetricsRegistry metricsRegistry;

    /**
     * Constructs the engine with its collaborators.
     *
     * @param agents          the agent for each stage
     * @param policyEngine    the policy guardrail engine
     * @param audit           the append-only audit log
     * @param metricsRegistry the reliability-metrics registry
     */
    public OrchestrationEngine(Map<Stage, Agent> agents, PolicyEngine policyEngine,
                               AuditLog audit, MetricsRegistry metricsRegistry) {
        this.agents = Map.copyOf(agents);
        this.policyEngine = policyEngine;
        this.audit = audit;
        this.metricsRegistry = metricsRegistry;
    }

    /**
     * Drives a run to completion: validates the DAG, executes ready stages in parallel rounds
     * with gates/retries/approvals, and finishes by completing, rolling back, or safe-stopping.
     *
     * @param run    the run to execute (its graph and context are mutated in place)
     * @param config the approval handler, retry policy, error budget, and optional kill-switch
     * @return the run's reliability metrics
     */
    public RunMetrics execute(Run run, RunConfig config) {
        // Reuse metrics if this run has executed before (e.g. after a re-plan), so counters
        // such as replans accumulate across executions.
        RunMetrics existing = metricsRegistry.get(run.id());
        final RunMetrics metrics = existing != null ? existing : metricsRegistry.create(run.id());
        SafeStopController safeStop = new SafeStopController();
        audit.record(run.id(), Type.RUN_CREATED, null, "run created for: " + run.requirement().title());

        // Whole-run entry gate: the graph must be a valid DAG.
        run.graph().validateAcyclic();
        run.setState(RunState.RUNNING);
        audit.record(run.id(), Type.RUN_STARTED, null,
                "scenario=" + run.requirement().scenarioType());

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            while (!run.graph().allTerminal()) {
                if (safeStop.tripped()) {
                    break;
                }
                // Auto safe-stop when the error budget is exhausted.
                if (metrics.stagesFailed() > config.errorBudget()) {
                    safeStop.trip("error budget exceeded (" + metrics.stagesFailed() + " failures)");
                    audit.record(run.id(), Type.SAFE_STOP, null, safeStop.reason());
                    break;
                }

                List<StageNode> frontier = run.graph().readyFrontier();
                if (frontier.isEmpty()) {
                    break; // nothing runnable: either done, failed, or deadlocked
                }

                // Operator kill-switch: stop before a chosen stage (demonstration of safe-stop).
                if (config.stopBeforeStage() != null
                        && frontier.stream().anyMatch(n -> n.stage() == config.stopBeforeStage())) {
                    safeStop.trip("operator stop before " + config.stopBeforeStage());
                    audit.record(run.id(), Type.SAFE_STOP, config.stopBeforeStage(), safeStop.reason());
                    break;
                }

                for (StageNode node : frontier) {
                    node.setState(StageState.READY);
                    audit.record(run.id(), Type.STAGE_READY, node.stage(), "ready");
                }

                // Parallel execution of the frontier, then a synchronization join.
                List<Future<NodeOutcome>> futures = frontier.stream()
                        .map(node -> pool.submit(() -> runNode(run, node, config, metrics)))
                        .toList();
                boolean unrecoverable = false;
                for (Future<NodeOutcome> f : futures) {
                    try {
                        if (f.get() == NodeOutcome.FAILED) {
                            unrecoverable = true;
                        }
                    } catch (Exception e) {
                        unrecoverable = true;
                    }
                }
                if (unrecoverable) {
                    break;
                }
            }

            if (run.graph().anyFailed()) {
                rollback(run, metrics);
                run.setState(RunState.ROLLED_BACK);
                audit.record(run.id(), Type.RUN_ROLLED_BACK, null, "run rolled back after failure");
            } else if (safeStop.tripped()) {
                rollback(run, metrics);
                run.setState(RunState.STOPPED);
                audit.record(run.id(), Type.RUN_STOPPED, null, "safe-stop: " + safeStop.reason());
            } else {
                run.setState(RunState.COMPLETED);
                audit.record(run.id(), Type.RUN_COMPLETED, null, "all stages complete");
            }
        } finally {
            metrics.markFinished();
        }
        return metrics;
    }

    /**
     * Executes one stage through its full lifecycle: entry gate, bounded retrying agent calls,
     * exit gate, policy gate, approval checkpoint, then commit to the context.
     *
     * @param run     the owning run
     * @param node    the stage node to execute
     * @param config  the run configuration
     * @param metrics the run metrics to update
     * @return the node outcome (SUCCEEDED, SKIPPED, or FAILED)
     */
    private NodeOutcome runNode(Run run, StageNode node, RunConfig config, RunMetrics metrics) {
        node.setState(StageState.RUNNING);
        node.markStarted();
        audit.record(run.id(), Type.STAGE_STARTED, node.stage(), "attempt budget=" + node.maxAttempts());

        GateResult entry = Gates.entry(node, run.context());
        audit.record(run.id(), Type.ENTRY_GATE, node.stage(), entry.reason());
        if (!entry.passed()) {
            return fail(run, node, metrics, entry.reason());
        }

        Agent agent = agents.get(node.stage());
        RetryPolicy retry = config.retryPolicy();

        while (node.attempts() < Math.min(node.maxAttempts(), retry.maxAttempts())) {
            int attempt = node.incrementAttempts();
            try {
                String fault = node.stage() == Stage.IMPLEMENTATION ? run.faultMode() : null;
                AgentResult result = agent.execute(new AgentContext(
                        run.requirement(), run.context(), attempt, node.lastFailureReason(), fault));
                metrics.recordLlmCall(result.tokensUsed());
                audit.record(run.id(), Type.AGENT_CALL, node.stage(),
                        "attempt " + attempt + " produced " + result.artifact().type(),
                        Map.of("tokens", String.valueOf(result.tokensUsed())));

                // Exit gate: independent validation of the output quality.
                GateResult exit = Gates.exit(node.stage(), result);
                audit.record(run.id(), Type.EXIT_GATE, node.stage(), exit.reason());
                if (!exit.passed()) {
                    if (retryOrExhaust(run, node, metrics, exit.reason(), attempt, retry)) {
                        continue;
                    }
                    break;
                }

                // Policy gate.
                List<Violation> violations = policyEngine.evaluate(result.artifact());
                for (Violation v : violations) {
                    audit.record(run.id(), Type.POLICY_VIOLATION, node.stage(),
                            v.severity() + " " + v.ruleId() + ": " + v.message());
                }
                if (PolicyEngine.hasBlocker(violations)) {
                    audit.record(run.id(), Type.POLICY_GATE, node.stage(), "blocked by policy");
                    if (retryOrExhaust(run, node, metrics, "policy blocker", attempt, retry)) {
                        continue;
                    }
                    break;
                }

                // Approval checkpoint for high-impact actions.
                boolean needApproval = node.requiresApproval()
                        || node.riskLevel() == com.schwab.agentic.domain.Enums.RiskLevel.HIGH
                        || !violations.isEmpty();
                if (needApproval) {
                    node.setState(StageState.AWAITING_APPROVAL);
                    String reason = violations.isEmpty() ? "high-impact stage" : "policy warnings present";
                    audit.record(run.id(), Type.APPROVAL_REQUESTED, node.stage(), reason);
                    ApprovalDecision decision = config.approvalHandler().decide(
                            new ApprovalRequest(run.id(), node.stage(), node.riskLevel(), reason));
                    metrics.recordApproval();
                    if (!decision.approved()) {
                        audit.record(run.id(), Type.APPROVAL_REJECTED, node.stage(), decision.note());
                        return fail(run, node, metrics, "approval rejected: " + decision.note());
                    }
                    node.setApproved(true);
                    audit.record(run.id(), Type.APPROVAL_GRANTED, node.stage(), "by " + decision.approver());
                }

                // Commit: publish the artifact and its decisions to the shared context.
                run.context().putArtifact(node.stage(), result.artifact());
                for (Decision d : result.decisions()) {
                    run.context().addDecision(d);
                    audit.record(run.id(), Type.DECISION, node.stage(), d.summary());
                }
                node.setState(StageState.SUCCEEDED);
                node.markFinished();
                metrics.recordStageSuccess(node.stage(), node.latencyMillis());
                audit.record(run.id(), Type.STAGE_SUCCEEDED, node.stage(),
                        "latency_ms=" + node.latencyMillis());
                return NodeOutcome.SUCCEEDED;

            } catch (RuntimeException e) {
                if (retryOrExhaust(run, node, metrics, "agent error: " + e.getMessage(), attempt, retry)) {
                    continue;
                }
                break;
            }
        }

        // Retries exhausted -> fallback.
        return fallback(run, node, metrics);
    }

    /**
     * Records a failed attempt and decides whether to retry.
     *
     * @param run     the owning run
     * @param node    the failing stage node
     * @param metrics the run metrics to update
     * @param reason  why the attempt failed
     * @param attempt the attempt number that just failed
     * @param retry   the retry policy
     * @return {@code true} if another attempt should be made (after sleeping the backoff);
     *         {@code false} if the retry budget is exhausted
     */
    private boolean retryOrExhaust(Run run, StageNode node, RunMetrics metrics, String reason,
                                   int attempt, RetryPolicy retry) {
        node.setLastFailureReason(reason);
        metrics.recordStageFailure(node.stage());
        audit.record(run.id(), Type.STAGE_FAILED, node.stage(),
                "attempt " + attempt + " failed: " + reason);
        boolean canRetry = node.attempts() < Math.min(node.maxAttempts(), retry.maxAttempts());
        if (canRetry) {
            metrics.recordRetry();
            audit.record(run.id(), Type.STAGE_RETRY, node.stage(), "retrying (self-correction)");
            retry.sleepBackoff(attempt);
        }
        return canRetry;
    }

    /**
     * Applies the fallback strategy after retries are exhausted: DOCUMENTATION is skipped with
     * a placeholder (non-critical), every other stage fails and causes the run to roll back.
     *
     * @param run     the owning run
     * @param node    the exhausted stage node
     * @param metrics the run metrics to update
     * @return SKIPPED for documentation, otherwise FAILED
     */
    private NodeOutcome fallback(Run run, StageNode node, RunMetrics metrics) {
        if (node.stage() == Stage.DOCUMENTATION) {
            Artifact placeholder = Artifact.of(
                    "doc-fallback-" + run.id(), Stage.DOCUMENTATION, "documentation-output",
                    "DOC_SUMMARY: (auto-fallback) documentation deferred to a follow-up task.",
                    run.context().nextVersion(), List.of());
            run.context().putArtifact(Stage.DOCUMENTATION, placeholder);
            node.setState(StageState.SKIPPED);
            node.markFinished();
            audit.record(run.id(), Type.STAGE_SKIPPED, node.stage(),
                    "non-critical stage skipped via fallback");
            return NodeOutcome.SKIPPED;
        }
        node.setState(StageState.FAILED);
        node.markFinished();
        audit.record(run.id(), Type.STAGE_FAILED, node.stage(),
                "retries exhausted; no safe fallback -> run will roll back");
        return NodeOutcome.FAILED;
    }

    /**
     * Marks a node failed terminally (no further retries) and records the failure.
     *
     * @param run     the owning run
     * @param node    the failed stage node
     * @param metrics the run metrics to update
     * @param reason  the failure reason
     * @return {@link NodeOutcome#FAILED}
     */
    private NodeOutcome fail(Run run, StageNode node, RunMetrics metrics, String reason) {
        node.setLastFailureReason(reason);
        node.setState(StageState.FAILED);
        node.markFinished();
        metrics.recordStageFailure(node.stage());
        audit.record(run.id(), Type.STAGE_FAILED, node.stage(), reason);
        return NodeOutcome.FAILED;
    }

    /**
     * Saga-style rollback: compensate succeeded stages in reverse completion order. Here that
     * means invalidating each stage's published artifact and marking the node rolled back. Real
     * compensations (revert a branch, delete a deployment) would hang off the same hook.
     *
     * @param run     the owning run
     * @param metrics the run metrics to update
     */
    private void rollback(Run run, RunMetrics metrics) {
        List<StageNode> succeeded = run.graph().nodes().stream()
                .filter(n -> n.state() == StageState.SUCCEEDED)
                .sorted(Comparator.comparingLong(StageNode::latencyMillis))
                .toList();
        // Reverse completion order.
        for (int i = succeeded.size() - 1; i >= 0; i--) {
            StageNode node = succeeded.get(i);
            run.context().invalidate(node.stage());
            node.setState(StageState.ROLLED_BACK);
            metrics.recordRollback();
            audit.record(run.id(), Type.STAGE_ROLLED_BACK, node.stage(), "compensated");
        }
    }

    // ----- Dynamic re-planning -----

    /**
     * Re-plan after an upstream output changes (e.g. the requirement is clarified post-hoc).
     * The revised stage and all its transitive dependents are invalidated and reset to PENDING
     * so the next {@link #execute} re-derives them with the new context — demonstrating
     * non-linear, stateful re-execution rather than a one-shot pipeline.
     *
     * @param run          the run to re-plan (its graph/context are mutated)
     * @param changedStage the upstream stage whose output changed
     */
    public void replan(Run run, Stage changedStage) {
        var dependents = run.graph().transitiveDependents(changedStage);
        audit.record(run.id(), Type.REPLAN, changedStage,
                "upstream changed; invalidating dependents " + dependents);
        for (Stage s : dependents) {
            run.graph().node(s).setState(StageState.PENDING);
            run.context().invalidate(s);
        }
        RunMetrics m = metricsRegistry.get(run.id());
        if (m != null) {
            m.recordReplan();
        }
    }
}
