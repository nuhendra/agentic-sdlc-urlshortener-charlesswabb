package com.schwab.agentic.domain;

import com.schwab.agentic.domain.Enums.Stage;
import com.schwab.agentic.domain.Enums.StageState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An explicit dependency graph (DAG) of {@link StageNode}s.
 *
 * <p>The graph is the backbone of non-linear, stateful execution: it exposes the set of
 * currently runnable nodes (the "frontier"), supports parallel independent paths with a
 * synchronization join, validates acyclicity at entry, and propagates staleness to
 * transitive dependents when re-planning.
 */
public final class DependencyGraph {

    private final Map<Stage, StageNode> nodes = new LinkedHashMap<>();

    /**
     * Adds a node to the graph.
     *
     * @param node the node to add (keyed by its stage)
     * @return this graph, for chaining
     */
    public DependencyGraph addNode(StageNode node) {
        nodes.put(node.stage(), node);
        return this;
    }

    /**
     * Looks up a node by stage.
     *
     * @param stage the stage to look up
     * @return the node for that stage
     * @throws IllegalArgumentException if no such node exists
     */
    public StageNode node(Stage stage) {
        StageNode n = nodes.get(stage);
        if (n == null) {
            throw new IllegalArgumentException("no such node: " + stage);
        }
        return n;
    }

    /**
     * Returns all nodes in insertion order.
     *
     * @return a copy of the node list
     */
    public List<StageNode> nodes() {
        return new ArrayList<>(nodes.values());
    }

    /**
     * Validates the graph has no cycles and no dangling dependencies. This is the engine's
     * <b>entry gate for the whole run</b>.
     *
     * @throws IllegalStateException if a dependency references a missing stage, or a cycle exists
     */
    public void validateAcyclic() {
        for (StageNode n : nodes.values()) {
            for (Stage dep : n.dependencies()) {
                if (!nodes.containsKey(dep)) {
                    throw new IllegalStateException(
                            "stage " + n.stage() + " depends on missing stage " + dep);
                }
            }
        }
        // Kahn's algorithm: if we cannot remove all nodes, a cycle exists.
        Map<Stage, Integer> indegree = new HashMap<>();
        for (StageNode n : nodes.values()) {
            indegree.put(n.stage(), n.dependencies().size());
        }
        Deque<Stage> queue = new ArrayDeque<>();
        indegree.forEach((s, d) -> {
            if (d == 0) {
                queue.add(s);
            }
        });
        int removed = 0;
        while (!queue.isEmpty()) {
            Stage s = queue.poll();
            removed++;
            for (StageNode other : nodes.values()) {
                if (other.dependencies().contains(s)) {
                    int d = indegree.get(other.stage()) - 1;
                    indegree.put(other.stage(), d);
                    if (d == 0) {
                        queue.add(other.stage());
                    }
                }
            }
        }
        if (removed != nodes.size()) {
            throw new IllegalStateException("dependency graph contains a cycle");
        }
    }

    /**
     * Computes the current execution frontier.
     *
     * <p>A node is on the frontier when it is PENDING and every dependency has SUCCEEDED (or
     * been SKIPPED via fallback). More than one node here means independent parallel paths are
     * ready at once.
     *
     * @return the list of runnable nodes (possibly empty)
     */
    public List<StageNode> readyFrontier() {
        List<StageNode> ready = new ArrayList<>();
        for (StageNode n : nodes.values()) {
            if (n.state() != StageState.PENDING) {
                continue;
            }
            boolean depsDone = n.dependencies().stream()
                    .allMatch(dep -> {
                        StageState s = node(dep).state();
                        // A SKIPPED dependency (fallback) still unblocks its dependents.
                        return s == StageState.SUCCEEDED || s == StageState.SKIPPED;
                    });
            if (depsDone) {
                ready.add(n);
            }
        }
        return ready;
    }

    /**
     * Returns the direct dependents of a stage (nodes that list it as a dependency).
     *
     * @param stage the stage whose dependents to find
     * @return the directly dependent nodes (possibly empty)
     */
    public List<StageNode> directDependents(Stage stage) {
        List<StageNode> dependents = new ArrayList<>();
        for (StageNode n : nodes.values()) {
            if (n.dependencies().contains(stage)) {
                dependents.add(n);
            }
        }
        return dependents;
    }

    /**
     * Computes the transitive closure of a stage's dependents, used by re-planning to
     * invalidate everything downstream of a revised stage.
     *
     * @param stage the stage whose transitive dependents to compute
     * @return the set of all stages reachable as dependents (excluding {@code stage} itself)
     */
    public Set<Stage> transitiveDependents(Stage stage) {
        Set<Stage> result = new HashSet<>();
        Deque<Stage> stack = new ArrayDeque<>();
        stack.push(stage);
        while (!stack.isEmpty()) {
            Stage current = stack.pop();
            for (StageNode dep : directDependents(current)) {
                if (result.add(dep.stage())) {
                    stack.push(dep.stage());
                }
            }
        }
        return result;
    }

    /**
     * Reports whether every node has reached a terminal state.
     *
     * @return {@code true} if all nodes are SUCCEEDED, ROLLED_BACK, or SKIPPED
     */
    public boolean allTerminal() {
        return nodes.values().stream().allMatch(StageNode::isTerminal);
    }

    /**
     * Reports whether any node has failed.
     *
     * @return {@code true} if at least one node is in the FAILED state
     */
    public boolean anyFailed() {
        return nodes.values().stream().anyMatch(n -> n.state() == StageState.FAILED);
    }
}
