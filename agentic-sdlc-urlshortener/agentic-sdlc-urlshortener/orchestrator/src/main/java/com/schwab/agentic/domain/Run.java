package com.schwab.agentic.domain;

import com.schwab.agentic.domain.Enums.RunState;

import java.time.Instant;

/**
 * The run aggregate: a requirement, its execution graph, the shared context, and lifecycle
 * state. One {@code Run} is one requirement flowing through the governed SDLC pipeline.
 */
public final class Run {

    private final String id;
    private final Requirement requirement;
    private final DependencyGraph graph;
    private final RunContext context;
    private final Instant createdAt = Instant.now();

    /** Optional fault-injection switch used by the governance demo (null in normal use). */
    private final String faultMode;

    private volatile RunState state = RunState.CREATED;

    /**
     * Creates a run.
     *
     * @param id          unique run id
     * @param requirement the requirement to deliver
     * @param graph       the execution graph to drive
     * @param faultMode   optional fault-injection switch, or {@code null} for normal runs
     */
    public Run(String id, Requirement requirement, DependencyGraph graph, String faultMode) {
        this.id = id;
        this.requirement = requirement;
        this.graph = graph;
        this.context = new RunContext(requirement);
        this.faultMode = faultMode;
    }

    /** Returns the unique run id. */
    public String id() {
        return id;
    }

    /** Returns the requirement this run delivers. */
    public Requirement requirement() {
        return requirement;
    }

    /** Returns the execution graph. */
    public DependencyGraph graph() {
        return graph;
    }

    /** Returns the shared, versioned run context. */
    public RunContext context() {
        return context;
    }

    /** Returns the current run lifecycle state. */
    public RunState state() {
        return state;
    }

    /** Sets the current run lifecycle state. */
    public void setState(RunState state) {
        this.state = state;
    }

    /** Returns the fault-injection switch, or {@code null} in normal runs. */
    public String faultMode() {
        return faultMode;
    }

    /** Returns the instant the run was created. */
    public Instant createdAt() {
        return createdAt;
    }
}
