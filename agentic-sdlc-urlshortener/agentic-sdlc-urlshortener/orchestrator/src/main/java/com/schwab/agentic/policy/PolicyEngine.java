package com.schwab.agentic.policy;

import com.schwab.agentic.domain.Artifact;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Embeds policy guardrails for security, compliance, and change control. The engine runs every
 * rule over a produced artifact and returns violations; a BLOCKER violation fails the policy
 * gate (and, if unrecoverable, triggers rollback).
 */
public final class PolicyEngine {

    /** Severity of a policy violation. */
    public enum Severity {WARN, BLOCKER}

    /**
     * A single policy violation.
     *
     * @param ruleId   the id of the rule that fired
     * @param severity the violation severity
     * @param message  a human-readable description
     */
    public record Violation(String ruleId, Severity severity, String message) {
    }

    /** A policy rule evaluated against a produced artifact. */
    public interface PolicyRule {
        /** @return the stable rule id (used in audit messages) */
        String id();

        /**
         * Evaluates the rule against an artifact.
         *
         * @param artifact the artifact to inspect
         * @return any violations found (empty if the artifact is clean)
         */
        List<Violation> check(Artifact artifact);
    }

    private final List<PolicyRule> rules;

    /**
     * Creates an engine from a fixed rule set.
     *
     * @param rules the rules to run (defensively copied)
     */
    public PolicyEngine(List<PolicyRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * Builds an engine with the default security and change-control rules.
     *
     * @return a ready-to-use {@link PolicyEngine}
     */
    public static PolicyEngine withDefaults() {
        return new PolicyEngine(List.of(
                new NoHardcodedSecretsRule(),
                new NoSqlConcatenationRule(),
                new ChangeSizeRule(400),
                new ProtectedPathRule()));
    }

    /**
     * Runs all rules over an artifact.
     *
     * @param artifact the artifact to evaluate
     * @return the combined list of violations across all rules
     */
    public List<Violation> evaluate(Artifact artifact) {
        List<Violation> all = new ArrayList<>();
        for (PolicyRule rule : rules) {
            all.addAll(rule.check(artifact));
        }
        return all;
    }

    /**
     * Reports whether any violation is blocking.
     *
     * @param violations the violations to inspect
     * @return {@code true} if at least one has {@link Severity#BLOCKER}
     */
    public static boolean hasBlocker(List<Violation> violations) {
        return violations.stream().anyMatch(v -> v.severity() == Severity.BLOCKER);
    }

    // ----- Security -----

    /** Blocks hardcoded credentials/secrets in produced code. */
    public static final class NoHardcodedSecretsRule implements PolicyRule {
        private static final Pattern SECRET = Pattern.compile(
                "(sk-live-[A-Za-z0-9]+)|(AKIA[0-9A-Z]{8,})|(?i)(password|secret|api[_-]?key)\\s*=\\s*\"[^\"]+\"");

        /** {@inheritDoc} */
        @Override
        public String id() {
            return "SEC-001-no-hardcoded-secrets";
        }

        /** Flags a BLOCKER if the content contains a secret-looking literal. {@inheritDoc} */
        @Override
        public List<Violation> check(Artifact artifact) {
            if (artifact.content() != null && SECRET.matcher(artifact.content()).find()) {
                return List.of(new Violation(id(), Severity.BLOCKER,
                        "hardcoded secret/credential detected in produced code"));
            }
            return List.of();
        }
    }

    /** Blocks SQL built by string concatenation (injection risk). */
    public static final class NoSqlConcatenationRule implements PolicyRule {
        private static final Pattern SQL_CONCAT = Pattern.compile(
                "(?i)(select|insert|update|delete)\\b[^\";]*\"\\s*\\+\\s*\\w");

        /** {@inheritDoc} */
        @Override
        public String id() {
            return "SEC-002-no-sql-string-concat";
        }

        /** Flags a BLOCKER if SQL appears to be concatenated from strings. {@inheritDoc} */
        @Override
        public List<Violation> check(Artifact artifact) {
            if (artifact.content() != null && SQL_CONCAT.matcher(artifact.content()).find()) {
                return List.of(new Violation(id(), Severity.BLOCKER,
                        "SQL appears to be built by string concatenation; use parameterized queries"));
            }
            return List.of();
        }
    }

    // ----- Change control -----

    /** Flags oversized changes that exceed the safe-change budget. */
    public static final class ChangeSizeRule implements PolicyRule {
        private final int maxLines;

        /**
         * @param maxLines the maximum changed-line budget before warning
         */
        public ChangeSizeRule(int maxLines) {
            this.maxLines = maxLines;
        }

        /** {@inheritDoc} */
        @Override
        public String id() {
            return "CHG-001-change-size";
        }

        /** Flags a WARN if {@code LINES_CHANGED} exceeds the budget. {@inheritDoc} */
        @Override
        public List<Violation> check(Artifact artifact) {
            String lines = extract(artifact.content(), "LINES_CHANGED");
            try {
                if (!lines.isBlank() && Integer.parseInt(lines.trim()) > maxLines) {
                    return List.of(new Violation(id(), Severity.WARN,
                            "change of " + lines + " lines exceeds budget of " + maxLines
                                    + "; recommend splitting"));
                }
            } catch (NumberFormatException ignored) {
                // no parseable count; nothing to flag
            }
            return List.of();
        }

        /**
         * Extracts a numeric {@code KEY: value} field from content.
         *
         * @param content the text to search (may be {@code null})
         * @param key     the field name
         * @return the digits found, or an empty string
         */
        private static String extract(String content, String key) {
            if (content == null) {
                return "";
            }
            var m = Pattern.compile("(?m)^\\s*" + key + "\\s*:\\s*(\\d+)").matcher(content);
            return m.find() ? m.group(1) : "";
        }
    }

    /** Requires extra scrutiny when a change touches protected/sensitive paths. */
    public static final class ProtectedPathRule implements PolicyRule {
        private static final Pattern PROTECTED = Pattern.compile(
                "(?i)(SecurityConfig|application-prod|schema\\.sql|pom\\.xml|Dockerfile)");

        /** {@inheritDoc} */
        @Override
        public String id() {
            return "CHG-002-protected-path";
        }

        /** Flags a WARN (recommending approval) if a protected path is touched. {@inheritDoc} */
        @Override
        public List<Violation> check(Artifact artifact) {
            if (artifact.content() != null && PROTECTED.matcher(artifact.content()).find()) {
                return List.of(new Violation(id(), Severity.WARN,
                        "change touches a protected path; human approval recommended"));
            }
            return List.of();
        }
    }
}
