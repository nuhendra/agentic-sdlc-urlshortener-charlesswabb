package com.schwab.agentic.engine;

import com.schwab.agentic.domain.Enums.Stage;
import com.schwab.agentic.gates.ApprovalHandler;

/**
 * Per-run execution configuration: the approval handler, retry policy, the error budget that
 * auto-trips safe-stop, and an optional operator "stop before this stage" kill-switch used to
 * demonstrate safe-stop deterministically.
 */
public record RunConfig(
        ApprovalHandler approvalHandler,
        RetryPolicy retryPolicy,
        int errorBudget,
        Stage stopBeforeStage) {

    /** @return a default config: auto-approve, default retries, error budget 6, no kill-switch */
    public static RunConfig defaults() {
        return new RunConfig(ApprovalHandler.autoApprove(), RetryPolicy.defaults(), 6, null);
    }

    /**
     * Returns a copy with a different approval handler.
     *
     * @param handler the approval handler to use
     * @return a new config
     */
    public RunConfig withApproval(ApprovalHandler handler) {
        return new RunConfig(handler, retryPolicy, errorBudget, stopBeforeStage);
    }

    /**
     * Returns a copy that safe-stops before a given stage (operator kill-switch demo).
     *
     * @param stage the stage to stop before
     * @return a new config
     */
    public RunConfig withStopBefore(Stage stage) {
        return new RunConfig(approvalHandler, retryPolicy, errorBudget, stage);
    }
}
