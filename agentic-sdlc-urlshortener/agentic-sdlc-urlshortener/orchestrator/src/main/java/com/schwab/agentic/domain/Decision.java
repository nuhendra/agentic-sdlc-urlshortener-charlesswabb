package com.schwab.agentic.domain;

import com.schwab.agentic.domain.Enums.Stage;

import java.time.Instant;

/**
 * A recorded decision with its rationale — part of the audit-grade decision lineage.
 * Decisions are never mutated; a changed decision is a new record.
 */
public record Decision(
        String id,
        Stage stage,
        String summary,
        String rationale,
        Instant madeAt) {

    /**
     * Factory for a decision stamped with the current instant.
     *
     * @param id        unique decision id
     * @param stage     the stage that made the decision
     * @param summary   a short description of the decision
     * @param rationale why the decision was made
     * @return a new {@link Decision}
     */
    public static Decision of(String id, Stage stage, String summary, String rationale) {
        return new Decision(id, stage, summary, rationale, Instant.now());
    }
}
