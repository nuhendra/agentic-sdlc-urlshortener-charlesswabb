package com.schwab.agentic.domain;

import com.schwab.agentic.domain.Enums.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The shared, versioned blackboard that preserves cross-stage context and decision lineage.
 *
 * <p>Each stage reads the artifacts produced upstream and writes its own. A monotonically
 * increasing {@code contextVersion} per stage lets the re-planner detect when a dependency's
 * output has changed since a downstream stage consumed it.
 */
public final class RunContext {

    private final Requirement requirement;
    private final Map<Stage, Artifact> artifacts = new ConcurrentHashMap<>();
    private final Map<Stage, Long> stageVersions = new ConcurrentHashMap<>();
    private final List<Decision> decisions = new CopyOnWriteArrayList<>();
    private final AtomicLong versionCounter = new AtomicLong(0);

    /**
     * Creates a context bound to a requirement.
     *
     * @param requirement the requirement this run addresses
     */
    public RunContext(Requirement requirement) {
        this.requirement = requirement;
    }

    /** Returns the requirement this run addresses. */
    public Requirement requirement() {
        return requirement;
    }

    /**
     * Stores (or replaces) a stage's artifact, bumping that stage's context version.
     *
     * @param stage    the producing stage
     * @param artifact the artifact to publish
     */
    public void putArtifact(Stage stage, Artifact artifact) {
        artifacts.put(stage, artifact);
        stageVersions.put(stage, versionCounter.incrementAndGet());
    }

    /**
     * Reads a stage's current artifact.
     *
     * @param stage the stage to read
     * @return the artifact if present, otherwise {@link Optional#empty()}
     */
    public Optional<Artifact> artifact(Stage stage) {
        return Optional.ofNullable(artifacts.get(stage));
    }

    /**
     * Returns the context version at which a stage last published.
     *
     * @param stage the stage to query
     * @return the version, or 0 if the stage has not published
     */
    public long versionOf(Stage stage) {
        return stageVersions.getOrDefault(stage, 0L);
    }

    /** Returns the current value of the monotonic version counter. */
    public long nextVersion() {
        return versionCounter.get();
    }

    /**
     * Appends a decision to the lineage.
     *
     * @param decision the decision to record
     */
    public void addDecision(Decision decision) {
        decisions.add(decision);
    }

    /** Returns a snapshot of all recorded decisions. */
    public List<Decision> decisions() {
        return new ArrayList<>(decisions);
    }

    /** Returns a snapshot of all currently published artifacts. */
    public List<Artifact> allArtifacts() {
        return new ArrayList<>(artifacts.values());
    }

    /**
     * Removes a stage's artifact when it is invalidated by re-planning.
     *
     * @param stage the stage whose artifact to drop
     */
    public void invalidate(Stage stage) {
        artifacts.remove(stage);
    }
}
