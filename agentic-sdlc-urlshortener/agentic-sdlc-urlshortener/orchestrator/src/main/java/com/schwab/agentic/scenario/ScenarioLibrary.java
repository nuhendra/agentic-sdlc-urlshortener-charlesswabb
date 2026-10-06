package com.schwab.agentic.scenario;

import com.schwab.agentic.domain.DependencyGraph;
import com.schwab.agentic.domain.Enums.RiskLevel;
import com.schwab.agentic.domain.Enums.ScenarioType;
import com.schwab.agentic.domain.Enums.Stage;
import com.schwab.agentic.domain.Requirement;
import com.schwab.agentic.domain.Run;
import com.schwab.agentic.domain.StageNode;

import java.util.Set;
import java.util.UUID;

/**
 * Builds the standard SDLC execution graph and the three assignment scenarios plus a
 * governance fault-injection case.
 *
 * <p>The graph is identical in shape across scenarios — the differences are in the requirement,
 * risk/approval configuration, and (for the fault case) an injected policy violation. The shape
 * is: REQUIREMENTS -> DESIGN -> IMPLEMENTATION -> {TESTING || DOCUMENTATION} -> RELEASE_READINESS,
 * giving a sequential spine, a parallel fan-out, and a synchronization join.
 */
public final class ScenarioLibrary {

    private ScenarioLibrary() {
    }

    /**
     * Builds the standard six-stage execution graph shared by all scenarios.
     *
     * @param requirementsNeedsApproval whether the requirements stage is a HIGH-risk approval
     *                                   checkpoint (used by the ambiguous scenario)
     * @return the configured dependency graph
     */
    private static DependencyGraph standardGraph(boolean requirementsNeedsApproval) {
        return new DependencyGraph()
                .addNode(new StageNode(Stage.REQUIREMENTS, Set.of(),
                        requirementsNeedsApproval ? RiskLevel.HIGH : RiskLevel.LOW,
                        requirementsNeedsApproval, 3))
                .addNode(new StageNode(Stage.DESIGN, Set.of(Stage.REQUIREMENTS),
                        RiskLevel.MEDIUM, false, 3))
                .addNode(new StageNode(Stage.IMPLEMENTATION, Set.of(Stage.DESIGN),
                        RiskLevel.HIGH, true, 3)) // code change is high-impact -> approval
                .addNode(new StageNode(Stage.TESTING, Set.of(Stage.IMPLEMENTATION),
                        RiskLevel.MEDIUM, false, 3))
                .addNode(new StageNode(Stage.DOCUMENTATION, Set.of(Stage.IMPLEMENTATION),
                        RiskLevel.LOW, false, 3))
                .addNode(new StageNode(Stage.RELEASE_READINESS,
                        Set.of(Stage.TESTING, Stage.DOCUMENTATION),
                        RiskLevel.HIGH, true, 2)); // go/no-go is high-impact -> approval
    }

    /** @return a greenfield run: add custom alias support (clean happy path) */
    public static Run greenfield() {
        Requirement req = new Requirement(
                shortId(),
                "Custom alias support",
                "Allow API callers to supply a custom alias when shortening a URL.",
                ScenarioType.GREENFIELD,
                null);
        return new Run(runId("greenfield"), req, standardGraph(false), null);
    }

    /** @return a brownfield run: harden the redirect and rate-limit resolve (drives a retry) */
    public static Run brownfield() {
        Requirement req = new Requirement(
                shortId(),
                "Harden redirect + rate-limit resolve",
                "Harden GET /{code} against open-redirect and add per-client rate limiting to "
                        + "the resolve path, without breaking existing behavior.",
                ScenarioType.BROWNFIELD,
                "url-shortener/controller.UrlController");
        return new Run(runId("brownfield"), req, standardGraph(false), null);
    }

    /** @return an ambiguous run: "make it more reliable" (forces a clarification checkpoint) */
    public static Run ambiguous() {
        Requirement req = new Requirement(
                shortId(),
                "Make it more reliable",
                "Make the URL shortener more reliable.",
                ScenarioType.AMBIGUOUS,
                null);
        // Requirements stage requires approval: the ambiguity checkpoint.
        return new Run(runId("ambiguous"), req, standardGraph(true), null);
    }

    /**
     * @return a greenfield-shaped run whose implementation leaks a secret, to exercise the
     *         policy gate and rollback
     */
    public static Run governanceFault() {
        Requirement req = new Requirement(
                shortId(),
                "Outbound webhook (fault-injected)",
                "Add an outbound webhook notifier when a short URL is created.",
                ScenarioType.GREENFIELD,
                null);
        return new Run(runId("governance"), req, standardGraph(false), "SECRET_LEAK");
    }

    /** @return a short random identifier (8 hex chars) */
    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * @param prefix a human-readable scenario prefix
     * @return a run id of the form {@code prefix-<shortId>}
     */
    private static String runId(String prefix) {
        return prefix + "-" + shortId();
    }
}
