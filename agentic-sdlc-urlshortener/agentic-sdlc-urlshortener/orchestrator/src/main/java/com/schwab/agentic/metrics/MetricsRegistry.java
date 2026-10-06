package com.schwab.agentic.metrics;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fleet-wide aggregation of {@link RunMetrics}, exposing the reliability KPIs across all runs.
 */
public final class MetricsRegistry {

    private final Map<String, RunMetrics> byRun = new ConcurrentHashMap<>();

    /**
     * Creates and registers a metrics object for a run.
     *
     * @param runId the run id
     * @return the newly created {@link RunMetrics}
     */
    public RunMetrics create(String runId) {
        RunMetrics m = new RunMetrics(runId);
        byRun.put(runId, m);
        return m;
    }

    /**
     * Looks up a run's metrics.
     *
     * @param runId the run id
     * @return the metrics, or {@code null} if the run is unknown
     */
    public RunMetrics get(String runId) {
        return byRun.get(runId);
    }

    /** Returns all registered run metrics. */
    public Collection<RunMetrics> all() {
        return byRun.values();
    }

    /**
     * Returns the mean per-run success rate across the fleet.
     *
     * @return the average of each run's success rate, or 1.0 if there are no runs
     */
    public double fleetSuccessRate() {
        return byRun.values().stream().mapToDouble(RunMetrics::successRate).average().orElse(1.0);
    }

    /**
     * Returns the mean number of retries per run.
     *
     * @return the average retry count, or 0 if there are no runs
     */
    public double avgRetriesPerRun() {
        return byRun.values().stream().mapToInt(RunMetrics::retries).average().orElse(0);
    }

    /**
     * Returns how often runs involve a rollback.
     *
     * @return the fraction of runs with at least one rollback (0..1), or 0 if there are no runs
     */
    public double rollbackFrequency() {
        long runs = byRun.size();
        if (runs == 0) {
            return 0;
        }
        long runsWithRollback = byRun.values().stream().filter(m -> m.rollbacks() > 0).count();
        return (double) runsWithRollback / runs;
    }

    /**
     * Returns the mean time to recovery across runs.
     *
     * @return the average MTTR in milliseconds, or 0 if there are no runs
     */
    public long avgMttrMillis() {
        return (long) byRun.values().stream().mapToLong(RunMetrics::mttrMillis).average().orElse(0);
    }

    /**
     * Returns the mean end-to-end latency across runs.
     *
     * @return the average latency in milliseconds, or 0 if there are no runs
     */
    public long avgEndToEndLatencyMillis() {
        return (long) byRun.values().stream()
                .mapToLong(RunMetrics::endToEndLatencyMillis).average().orElse(0);
    }

    /**
     * Renders the fleet KPIs as a compact single line.
     *
     * @return a formatted summary string
     */
    public String render() {
        return String.format(
                "runs=%d fleet_success_rate=%.0f%% avg_retries=%.2f rollback_freq=%.0f%% "
                        + "avg_mttr_ms=%d avg_e2e_ms=%d",
                byRun.size(), fleetSuccessRate() * 100, avgRetriesPerRun(),
                rollbackFrequency() * 100, avgMttrMillis(), avgEndToEndLatencyMillis());
    }
}
