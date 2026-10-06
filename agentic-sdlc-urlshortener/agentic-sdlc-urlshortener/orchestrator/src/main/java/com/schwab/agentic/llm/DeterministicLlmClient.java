package com.schwab.agentic.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A hermetic, offline LLM backend that returns realistic, deterministic stage outputs.
 *
 * <p>It reads a small control header the agents prepend to the prompt
 * ({@code #CTRL stage=.. scenario=.. attempt=.. fault=..}) and returns canned content in the
 * exact structured format the agents parse. This lets the whole engine — gates, retries,
 * rollback, re-planning, metrics — run and be asserted with zero network and zero cost.
 *
 * <p>The real model ({@link AnthropicLlmClient}) is instructed by the same system prompt to
 * emit the same fields, so swapping backends does not change the engine's contract.
 */
public final class DeterministicLlmClient implements LlmClient {

    /**
     * Returns canned, scenario-aware content based on the control header the agents prepend.
     *
     * @param request the prompt, whose user text carries the {@code #CTRL ...} header
     * @return a deterministic response with simulated token counts
     */
    @Override
    public LlmResponse complete(LlmRequest request) {
        String prompt = request.userPrompt();
        String stage = ctrl(prompt, "stage");
        String scenario = ctrl(prompt, "scenario");
        int attempt = parseIntOrDefault(ctrl(prompt, "attempt"), 1);
        String fault = ctrl(prompt, "fault");

        String text = switch (stage) {
            case "REQUIREMENTS" -> requirements(scenario);
            case "DESIGN" -> design(scenario);
            case "IMPLEMENTATION" -> implementation(scenario, attempt, fault);
            case "TESTING" -> testing(scenario, attempt);
            case "DOCUMENTATION" -> documentation(scenario);
            case "RELEASE_READINESS" -> release(scenario);
            default -> "NO_OP";
        };
        // Token counts are simulated proportional to text length.
        long inTok = request.userPrompt().length() / 4L;
        long outTok = text.length() / 4L;
        return new LlmResponse(text, inTok, outTok);
    }

    /** {@inheritDoc} */
    @Override
    public String backendId() {
        return "deterministic";
    }

    /** Returns canned requirements output, with high ambiguity for the ambiguous scenario. */
    private static String requirements(String scenario) {
        if ("AMBIGUOUS".equals(scenario)) {
            return """
                   AMBIGUITY_SCORE: 0.80
                   NORMALIZED_SPEC: "Make the service more reliable" is underspecified.
                   OPEN_QUESTIONS:
                     - Which reliability dimension: availability, data durability, or latency SLO?
                     - What is the target (e.g. 99.9% availability, p99 < 50ms)?
                     - Is multi-instance deployment in scope (shared cache / rate limit store)?
                   ACCEPTANCE_CRITERIA: pending clarification
                   """;
        }
        if ("BROWNFIELD".equals(scenario)) {
            return """
                   AMBIGUITY_SCORE: 0.15
                   NORMALIZED_SPEC: Harden GET /{code} against open-redirect and add per-client
                   rate limiting to the resolve path. Preserve existing 302 semantics and analytics.
                   ACCEPTANCE_CRITERIA:
                     - Only http/https destinations are ever returned.
                     - Resolve path rejects abusive bursts with HTTP 429.
                     - No regression in existing redirect/stats tests.
                   """;
        }
        return """
               AMBIGUITY_SCORE: 0.10
               NORMALIZED_SPEC: Add user-supplied custom alias support to POST /api/v1/urls.
               ACCEPTANCE_CRITERIA:
                 - Caller may request an alias; collisions return 409.
                 - Alias must match ^[a-zA-Z0-9_-]{4,32}$.
                 - Absent alias falls back to the existing Base62 id-derived code.
               """;
    }

    /** Returns canned design output naming impacted modules and additive-change risks. */
    private static String design(String scenario) {
        return """
               DESIGN_SUMMARY: Component and API design for "%s".
               IMPACTED_MODULES: web.UrlController, service.UrlShortenerService, domain.UrlMapping
               API_CHANGES: request/response schema extended; backward compatible.
               DATA_CHANGES: additive column(s) only; no destructive migration.
               RISKS: low-to-medium; additive change with feature-flag rollout.
               """.formatted(scenario);
    }

    /**
     * Returns canned implementation output; injects a secret leak under the SECRET_LEAK fault
     * and an incomplete first attempt for the brownfield scenario (to drive a retry).
     */
    private static String implementation(String scenario, int attempt, String fault) {
        // Fault injection: a BLOCKER-level secret leak to exercise the policy gate + rollback.
        if ("SECRET_LEAK".equals(fault)) {
            // Passes the exit gate (self-check complete) so the POLICY gate is what catches it.
            return """
                   DIFF_SUMMARY: implement feature; add outbound client.
                   LINES_CHANGED: 180
                   CODE:
                   String apiKey = "sk-live-AKIA1234567890SECRET";
                   // ... remainder of change ...
                   SELF_CHECK: complete
                   """;
        }
        // Brownfield first attempt is deliberately incomplete so the exit gate rejects it,
        // demonstrating a bounded retry that recovers on attempt 2.
        if ("BROWNFIELD".equals(scenario) && attempt == 1) {
            return """
                   DIFF_SUMMARY: partial implementation; open-redirect guard not yet added.
                   LINES_CHANGED: 40
                   CODE: // TODO: scheme validation missing
                   SELF_CHECK: incomplete
                   """;
        }
        return """
               DIFF_SUMMARY: implement normalized spec for "%s".
               LINES_CHANGED: 120
               CODE:
               PreparedStatement ps = conn.prepareStatement("SELECT * FROM url_mapping WHERE short_code = ?");
               // parameterized query; scheme-validated redirect target
               SELF_CHECK: complete
               """.formatted(scenario);
    }

    /** Returns canned testing output reporting passing tests and coverage. */
    private static String testing(String scenario, int attempt) {
        return """
               TESTS_PASSED: true
               COVERAGE: 0.86
               SUMMARY: 14 unit + 5 integration tests added/updated; all green.
               """;
    }

    /** Returns canned documentation output (README + changelog summary). */
    private static String documentation(String scenario) {
        return """
               DOC_SUMMARY: README + API reference updated for "%s".
               CHANGELOG: entry added under Unreleased.
               """.formatted(scenario);
    }

    /** Returns canned release-readiness output with a go decision and residual risk. */
    private static String release(String scenario) {
        return """
               GO: true
               CHECKLIST: tests green; docs updated; policy clean; rollback plan recorded.
               RESIDUAL_RISK: low
               """;
    }

    /**
     * Reads a {@code key=value} token from the {@code #CTRL} control header.
     *
     * @param prompt the user prompt containing the header
     * @param key    the control key to read
     * @return the value, or an empty string if absent
     */
    private static String ctrl(String prompt, String key) {
        Matcher m = Pattern.compile("#CTRL[^\\n]*\\b" + key + "=(\\S+)").matcher(prompt);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Parses an int, falling back to a default on null/blank/invalid input.
     *
     * @param s   the string to parse
     * @param def the fallback value
     * @return the parsed int, or {@code def}
     */
    private static int parseIntOrDefault(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
