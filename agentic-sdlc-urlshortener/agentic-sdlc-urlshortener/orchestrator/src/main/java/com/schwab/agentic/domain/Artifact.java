package com.schwab.agentic.domain;

import com.schwab.agentic.domain.Enums.Stage;

import java.time.Instant;
import java.util.List;

/**
 * An immutable engineering output produced by a stage (a spec, a design, code, tests, docs,
 * a release decision).
 *
 * <p>{@code derivedFrom} records the ids of the upstream artifacts this one was built from,
 * which — together with {@link Decision} — forms the <b>decision lineage</b>: every output
 * can be traced back through the inputs and choices that produced it.
 *
 * @param contextVersion the version of the producing stage's context when this was emitted;
 *                       used by re-planning to detect staleness.
 */
public record Artifact(
        String id,
        Stage stage,
        String type,
        String content,
        int version,
        long contextVersion,
        List<String> derivedFrom,
        Instant createdAt) {

    public Artifact {
        derivedFrom = derivedFrom == null ? List.of() : List.copyOf(derivedFrom);
    }

    /**
     * Factory for a first-version artifact, stamped with the current instant.
     *
     * @param id             unique artifact id
     * @param stage          the producing stage
     * @param type           a short artifact type label
     * @param content        the artifact body
     * @param contextVersion the producing stage's context version at emission time
     * @param derivedFrom    ids of the upstream artifacts this was built from (lineage)
     * @return a new {@link Artifact} at version 1
     */
    public static Artifact of(String id, Stage stage, String type, String content,
                              long contextVersion, List<String> derivedFrom) {
        return new Artifact(id, stage, type, content, 1, contextVersion, derivedFrom, Instant.now());
    }

    /**
     * Returns the length of the artifact content.
     *
     * @return the number of characters in {@code content}, or 0 if it is {@code null}
     */
    public int contentLength() {
        return content == null ? 0 : content.length();
    }
}
