package com.schwab.agentic.domain;

/**
 * Core enumerations for the orchestration domain, grouped for readability.
 */
public final class Enums {

    private Enums() {
    }

    /** The SDLC stages the orchestrator coordinates. Order is informational, not execution order. */
    public enum Stage {
        REQUIREMENTS,
        DESIGN,
        IMPLEMENTATION,
        TESTING,
        DOCUMENTATION,
        RELEASE_READINESS
    }

    /**
     * Per-stage lifecycle state. Execution is non-linear: a node can move back to PENDING
     * via STALE when an upstream output is revised (dynamic re-planning).
     */
    public enum StageState {
        PENDING,            // not yet runnable
        READY,              // all dependencies satisfied; eligible to run
        RUNNING,            // agent executing
        AWAITING_APPROVAL,  // paused at a human checkpoint
        SUCCEEDED,
        FAILED,
        ROLLED_BACK,
        SKIPPED,            // fallback decided to skip
        STALE               // upstream changed; must re-run
    }

    /** Overall run lifecycle. */
    public enum RunState {
        CREATED,
        RUNNING,
        PAUSED,       // awaiting a human approval
        COMPLETED,
        FAILED,
        STOPPED,      // safe-stop invoked
        ROLLED_BACK
    }

    /** Risk classification that drives approval checkpoints and policy strictness. */
    public enum RiskLevel {
        LOW, MEDIUM, HIGH
    }

    /** Scenario families from the assignment scope. */
    public enum ScenarioType {
        GREENFIELD, BROWNFIELD, AMBIGUOUS
    }
}
