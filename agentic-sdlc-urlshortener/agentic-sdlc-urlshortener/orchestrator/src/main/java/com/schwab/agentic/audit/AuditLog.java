package com.schwab.agentic.audit;

import com.schwab.agentic.domain.Enums.Stage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An append-only, immutable audit trail providing audit-grade observability and traceability.
 *
 * <p>Every state transition, gate evaluation, agent call, approval, retry, rollback, and
 * safe-stop is recorded with a monotonic sequence number and a correlation (run) id. The log
 * is never mutated or deleted — it is the system of record for "what happened and why".
 */
public final class AuditLog {

    /** Types of audited events. */
    public enum Type {
        RUN_CREATED, RUN_STARTED, RUN_COMPLETED, RUN_FAILED, RUN_STOPPED, RUN_ROLLED_BACK,
        STAGE_READY, STAGE_STARTED, STAGE_SUCCEEDED, STAGE_FAILED, STAGE_SKIPPED,
        STAGE_RETRY, STAGE_STALE, STAGE_ROLLED_BACK,
        ENTRY_GATE, EXIT_GATE, POLICY_GATE, APPROVAL_REQUESTED, APPROVAL_GRANTED, APPROVAL_REJECTED,
        AGENT_CALL, POLICY_VIOLATION, REPLAN, SAFE_STOP, DECISION
    }

    /**
     * A single immutable audit entry.
     *
     * @param seq     monotonic sequence number
     * @param runId   correlation id of the owning run
     * @param at      the instant the event was recorded
     * @param type    the event type
     * @param stage   the stage the event relates to, or {@code null} for run-level events
     * @param message a human-readable message
     * @param details additional structured key/value detail
     */
    public record Event(
            long seq,
            String runId,
            Instant at,
            Type type,
            Stage stage,
            String message,
            Map<String, String> details) {
    }

    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong(0);

    /**
     * Appends an event with structured detail.
     *
     * @param runId   the owning run id
     * @param type    the event type
     * @param stage   the related stage, or {@code null} for run-level events
     * @param message a human-readable message
     * @param details additional detail (copied; may be {@code null})
     * @return the recorded event
     */
    public Event record(String runId, Type type, Stage stage, String message,
                        Map<String, String> details) {
        Event e = new Event(seq.incrementAndGet(), runId, Instant.now(), type, stage, message,
                details == null ? Map.of() : Map.copyOf(details));
        events.add(e);
        return e;
    }

    /**
     * Appends an event with no structured detail.
     *
     * @param runId   the owning run id
     * @param type    the event type
     * @param stage   the related stage, or {@code null} for run-level events
     * @param message a human-readable message
     * @return the recorded event
     */
    public Event record(String runId, Type type, Stage stage, String message) {
        return record(runId, type, stage, message, Map.of());
    }

    /** Returns an immutable snapshot of all events across all runs. */
    public List<Event> events() {
        return List.copyOf(events);
    }

    /**
     * Returns the events for a single run, in order.
     *
     * @param runId the run to filter by
     * @return the matching events
     */
    public List<Event> eventsForRun(String runId) {
        return events.stream().filter(e -> e.runId().equals(runId)).toList();
    }

    /**
     * Renders a run's audit trail as human-readable text for the console demo and the
     * {@code /audit} endpoint.
     *
     * @param runId the run to render
     * @return a formatted multi-line string
     */
    public String render(String runId) {
        StringBuilder sb = new StringBuilder();
        for (Event e : eventsForRun(runId)) {
            sb.append(String.format("#%-4d %-20s %-18s %s%n",
                    e.seq(),
                    e.type(),
                    e.stage() == null ? "-" : e.stage(),
                    e.message()));
        }
        return sb.toString();
    }
}
