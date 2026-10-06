package com.schwab.agentic.domain;

import com.schwab.agentic.domain.Enums.RiskLevel;
import com.schwab.agentic.domain.Enums.Stage;
import com.schwab.agentic.domain.Enums.StageState;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A mutable node in the execution graph: one SDLC stage plus its runtime state, dependencies,
 * governance configuration, and timing.
 */
public final class StageNode {

    private final Stage stage;
    private final Set<Stage> dependencies;
    private final RiskLevel riskLevel;
    private final boolean requiresApproval;
    private final int maxAttempts;

    private volatile StageState state = StageState.PENDING;
    private volatile int attempts = 0;
    private volatile String lastFailureReason;
    private volatile long startedAtMillis;
    private volatile long finishedAtMillis;
    private volatile boolean approved;

    /**
     * Creates a stage node.
     *
     * @param stage            the SDLC stage this node represents
     * @param dependencies     the stages that must complete before this one may run
     * @param riskLevel        the risk classification driving approval/policy strictness
     * @param requiresApproval whether this stage always requires a human approval checkpoint
     * @param maxAttempts      the per-stage retry budget
     */
    public StageNode(Stage stage, Set<Stage> dependencies, RiskLevel riskLevel,
                     boolean requiresApproval, int maxAttempts) {
        this.stage = stage;
        this.dependencies = new LinkedHashSet<>(dependencies);
        this.riskLevel = riskLevel;
        this.requiresApproval = requiresApproval;
        this.maxAttempts = maxAttempts;
    }

    /** Returns the SDLC stage this node represents. */
    public Stage stage() {
        return stage;
    }

    /** Returns the stages this node depends on. */
    public Set<Stage> dependencies() {
        return dependencies;
    }

    /** Returns the node's risk classification. */
    public RiskLevel riskLevel() {
        return riskLevel;
    }

    /** Returns whether this stage always requires a human approval checkpoint. */
    public boolean requiresApproval() {
        return requiresApproval;
    }

    /** Returns the per-stage retry budget. */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** Returns the current lifecycle state. */
    public StageState state() {
        return state;
    }

    /** Sets the current lifecycle state. */
    public void setState(StageState state) {
        this.state = state;
    }

    /** Returns the number of attempts made so far. */
    public int attempts() {
        return attempts;
    }

    /**
     * Increments and returns the attempt counter.
     *
     * @return the new (post-increment) attempt number
     */
    public int incrementAttempts() {
        return ++attempts;
    }

    /** Returns the reason the most recent attempt failed, or {@code null}. */
    public String lastFailureReason() {
        return lastFailureReason;
    }

    /** Records the reason the most recent attempt failed. */
    public void setLastFailureReason(String reason) {
        this.lastFailureReason = reason;
    }

    /** Returns whether this stage's approval checkpoint has been granted. */
    public boolean approved() {
        return approved;
    }

    /** Records whether this stage's approval checkpoint has been granted. */
    public void setApproved(boolean approved) {
        this.approved = approved;
    }

    /** Marks the start time for latency measurement. */
    public void markStarted() {
        this.startedAtMillis = System.currentTimeMillis();
    }

    /** Marks the finish time for latency measurement. */
    public void markFinished() {
        this.finishedAtMillis = System.currentTimeMillis();
    }

    /**
     * Returns the stage's elapsed execution time.
     *
     * @return milliseconds between start and finish (or now, if still running); 0 if not started
     */
    public long latencyMillis() {
        if (startedAtMillis == 0) {
            return 0;
        }
        long end = finishedAtMillis == 0 ? System.currentTimeMillis() : finishedAtMillis;
        return end - startedAtMillis;
    }

    /**
     * Reports whether the node is in a terminal state.
     *
     * @return {@code true} if SUCCEEDED, ROLLED_BACK, or SKIPPED
     */
    public boolean isTerminal() {
        return state == StageState.SUCCEEDED
                || state == StageState.ROLLED_BACK
                || state == StageState.SKIPPED;
    }
}
