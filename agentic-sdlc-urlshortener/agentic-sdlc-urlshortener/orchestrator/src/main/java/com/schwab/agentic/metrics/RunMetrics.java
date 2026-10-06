package com.schwab.agentic.metrics;

import com.schwab.agentic.domain.Enums.Stage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reliability metrics for a single run. Aggregated across runs by {@link MetricsRegistry}.
 *
 * <p>Tracks the reliability signals the assignment calls out: success rate (derived),
 * retry frequency, rollback frequency, MTTR (mean time to recovery), and end-to-end latency.
 */
public final class RunMetrics {

    private final String runId;
    private final long startedAtMillis = System.currentTimeMillis();
    private volatile long finishedAtMillis;

    private final AtomicInteger stagesSucceeded = new AtomicInteger();
    private final AtomicInteger stagesFailed = new AtomicInteger();
    private final AtomicInteger retries = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();
    private final AtomicInteger approvals = new AtomicInteger();
    private final AtomicInteger replans = new AtomicInteger();
    private final AtomicInteger llmCalls = new AtomicInteger();
    private final AtomicLong llmTokens = new AtomicLong();

    private final Map<Stage, Long> stageLatencyMillis = new ConcurrentHashMap<>();

    // MTTR tracking: time between a stage first failing and the run recovering past it.
    private volatile long firstFailureAtMillis;
    private final AtomicLong totalRecoveryMillis = new AtomicLong();
    private final AtomicInteger recoveryCount = new AtomicInteger();

    /**
     * Creates a metrics object for a run.
     *
     * @param runId the run these metrics belong to
     */
    public RunMetrics(String runId) {
        this.runId = runId;
    }

    /** Returns the owning run id. */
    public String runId() {
        return runId;
    }

    /**
     * Records a successful stage and, if the run was in a failed state, closes out the MTTR
     * recovery interval.
     *
     * @param stage         the stage that succeeded
     * @param latencyMillis the stage's execution latency
     */
    public void recordStageSuccess(Stage stage, long latencyMillis) {
        stagesSucceeded.incrementAndGet();
        stageLatencyMillis.put(stage, latencyMillis);
        if (firstFailureAtMillis > 0) {
            totalRecoveryMillis.addAndGet(System.currentTimeMillis() - firstFailureAtMillis);
            recoveryCount.incrementAndGet();
            firstFailureAtMillis = 0;
        }
    }

    /**
     * Records a stage failure, starting an MTTR recovery interval if one is not already open.
     *
     * @param stage the stage that failed
     */
    public void recordStageFailure(Stage stage) {
        stagesFailed.incrementAndGet();
        if (firstFailureAtMillis == 0) {
            firstFailureAtMillis = System.currentTimeMillis();
        }
    }

    /** Records a retry attempt. */
    public void recordRetry() {
        retries.incrementAndGet();
    }

    /** Records a rollback (one compensated stage). */
    public void recordRollback() {
        rollbacks.incrementAndGet();
    }

    /** Records a human approval checkpoint being hit. */
    public void recordApproval() {
        approvals.incrementAndGet();
    }

    /** Records a dynamic re-plan. */
    public void recordReplan() {
        replans.incrementAndGet();
    }

    /**
     * Records an LLM call and its token usage.
     *
     * @param tokens the total tokens consumed by the call
     */
    public void recordLlmCall(long tokens) {
        llmCalls.incrementAndGet();
        llmTokens.addAndGet(tokens);
    }

    /** Marks the run finished, freezing end-to-end latency. */
    public void markFinished() {
        this.finishedAtMillis = System.currentTimeMillis();
    }

    /**
     * Returns the end-to-end latency.
     *
     * @return milliseconds from run start to finish (or now, if still running)
     */
    public long endToEndLatencyMillis() {
        long end = finishedAtMillis == 0 ? System.currentTimeMillis() : finishedAtMillis;
        return end - startedAtMillis;
    }

    /**
     * Returns the stage success rate.
     *
     * @return successes / (successes + failures), or 1.0 if no stages have resolved
     */
    public double successRate() {
        int total = stagesSucceeded.get() + stagesFailed.get();
        return total == 0 ? 1.0 : (double) stagesSucceeded.get() / total;
    }

    /**
     * Returns the mean time to recovery.
     *
     * @return average milliseconds from a failure to the next success, or 0 if none
     */
    public long mttrMillis() {
        return recoveryCount.get() == 0 ? 0 : totalRecoveryMillis.get() / recoveryCount.get();
    }

    /** Returns the number of stages that succeeded. */
    public int stagesSucceeded() {
        return stagesSucceeded.get();
    }

    /** Returns the number of stage failures (including retried attempts). */
    public int stagesFailed() {
        return stagesFailed.get();
    }

    /** Returns the number of retries performed. */
    public int retries() {
        return retries.get();
    }

    /** Returns the number of stages compensated by rollback. */
    public int rollbacks() {
        return rollbacks.get();
    }

    /** Returns the number of approval checkpoints hit. */
    public int approvals() {
        return approvals.get();
    }

    /** Returns the number of dynamic re-plans. */
    public int replans() {
        return replans.get();
    }

    /** Returns the number of LLM calls made. */
    public int llmCalls() {
        return llmCalls.get();
    }

    /** Returns the total LLM tokens consumed. */
    public long llmTokens() {
        return llmTokens.get();
    }

    /** Returns a snapshot of per-stage latencies. */
    public Map<Stage, Long> stageLatencyMillis() {
        return Map.copyOf(stageLatencyMillis);
    }

    /**
     * Renders the metrics as a compact single line for the console demo and the metrics endpoint.
     *
     * @return a formatted summary string
     */
    public String render() {
        return String.format(
                "success_rate=%.0f%% stages_ok=%d stages_failed=%d retries=%d rollbacks=%d "
                        + "approvals=%d replans=%d mttr_ms=%d e2e_ms=%d llm_calls=%d llm_tokens=%d",
                successRate() * 100, stagesSucceeded.get(), stagesFailed.get(), retries.get(),
                rollbacks.get(), approvals.get(), replans.get(), mttrMillis(),
                endToEndLatencyMillis(), llmCalls.get(), llmTokens.get());
    }
}
