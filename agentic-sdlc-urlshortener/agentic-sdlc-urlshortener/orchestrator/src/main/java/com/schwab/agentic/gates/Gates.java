package com.schwab.agentic.gates;

import com.schwab.agentic.agents.Agent.AgentResult;
import com.schwab.agentic.domain.Enums.Stage;
import com.schwab.agentic.domain.RunContext;
import com.schwab.agentic.domain.StageNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entry and exit gates. Entry gates verify preconditions before a stage runs; exit gates
 * validate the quality of a stage's output before it is allowed to satisfy its dependents.
 * Together they make the graph governed rather than a blind task chain.
 */
public final class Gates {

    private Gates() {
    }

    /**
     * The outcome of a gate evaluation.
     *
     * @param passed whether the gate passed
     * @param reason a short explanation (always set, including on pass)
     */
    public record GateResult(boolean passed, String reason) {
        /** @return a passing result */
        public static GateResult pass() {
            return new GateResult(true, "ok");
        }

        /**
         * @param reason why the gate failed
         * @return a failing result carrying the reason
         */
        public static GateResult fail(String reason) {
            return new GateResult(false, reason);
        }
    }

    /**
     * Entry gate: verifies all declared dependencies have produced an artifact in the context.
     *
     * @param node    the stage about to run
     * @param context the shared run context
     * @return a passing result, or a failing one naming the missing upstream artifact
     */
    public static GateResult entry(StageNode node, RunContext context) {
        for (Stage dep : node.dependencies()) {
            if (context.artifact(dep).isEmpty()) {
                return GateResult.fail("entry gate: missing upstream artifact from " + dep);
            }
        }
        return GateResult.pass();
    }

    /**
     * Exit gate: stage-specific validation of the produced output. This is where "the agent
     * said it is done" is independently checked against objective criteria.
     *
     * @param stage  the stage that produced the output
     * @param result the agent result to validate
     * @return a passing result, or a failing one explaining why the output was rejected
     */
    public static GateResult exit(Stage stage, AgentResult result) {
        String text = result.rawText();
        return switch (stage) {
            case REQUIREMENTS -> has(text, "NORMALIZED_SPEC")
                    ? GateResult.pass()
                    : GateResult.fail("exit gate: requirements missing NORMALIZED_SPEC");
            case DESIGN -> has(text, "DESIGN_SUMMARY") && has(text, "IMPACTED_MODULES")
                    ? GateResult.pass()
                    : GateResult.fail("exit gate: design missing summary or impacted modules");
            case IMPLEMENTATION -> "complete".equalsIgnoreCase(field(text, "SELF_CHECK"))
                    ? GateResult.pass()
                    : GateResult.fail("exit gate: implementation self-check not complete");
            case TESTING -> "true".equalsIgnoreCase(field(text, "TESTS_PASSED"))
                    ? GateResult.pass()
                    : GateResult.fail("exit gate: tests did not pass");
            case DOCUMENTATION -> has(text, "DOC_SUMMARY")
                    ? GateResult.pass()
                    : GateResult.fail("exit gate: documentation missing summary");
            case RELEASE_READINESS -> "true".equalsIgnoreCase(field(text, "GO"))
                    ? GateResult.pass()
                    : GateResult.fail("exit gate: release gate returned no-go");
        };
    }

    /**
     * Reports whether a {@code KEY: value} field is present and non-blank.
     *
     * @param text the text to search
     * @param key  the field name
     * @return {@code true} if the field exists with a non-blank value
     */
    private static boolean has(String text, String key) {
        return !field(text, key).isBlank();
    }

    /**
     * Extracts the value of a {@code KEY: value} field.
     *
     * @param text the text to search
     * @param key  the field name
     * @return the trimmed value, or an empty string if absent
     */
    private static String field(String text, String key) {
        if (text == null) {
            return "";
        }
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*:\\s*(.*)$").matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }
}
