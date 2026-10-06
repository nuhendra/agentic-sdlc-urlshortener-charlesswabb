package com.schwab.agentic.gates;

import com.schwab.agentic.domain.Enums.RiskLevel;
import com.schwab.agentic.domain.Enums.Stage;

import java.util.Map;

/**
 * The human-approval checkpoint abstraction. High-impact actions (high risk, or policy warnings,
 * or stages explicitly flagged) pause for a decision here.
 *
 * <p>In the REST deployment this is backed by a pending decision the engine blocks on until an
 * operator calls the approve/reject endpoint. In tests and the console demo it is backed by a
 * deterministic policy so runs are reproducible.
 */
public interface ApprovalHandler {

    record ApprovalRequest(String runId, Stage stage, RiskLevel riskLevel, String reason) {
    }

    /**
     * The outcome of an approval checkpoint.
     *
     * @param approved whether the action was approved
     * @param approver who (or what) decided
     * @param note     a short note (approval message or rejection reason)
     */
    record ApprovalDecision(boolean approved, String approver, String note) {
        /**
         * @param approver who approved
         * @return an approving decision
         */
        public static ApprovalDecision approve(String approver) {
            return new ApprovalDecision(true, approver, "approved");
        }

        /**
         * @param approver who rejected
         * @param note     why it was rejected
         * @return a rejecting decision
         */
        public static ApprovalDecision reject(String approver, String note) {
            return new ApprovalDecision(false, approver, note);
        }
    }

    /**
     * Decides a pending approval request.
     *
     * @param request the stage, risk level, and reason requiring approval
     * @return the approval decision
     */
    ApprovalDecision decide(ApprovalRequest request);

    /**
     * Deterministic handler: approves by default, rejects stages named in {@code rejects}.
     *
     * @param rejects a map from stage to rejection note for stages that should be denied
     * @return a handler implementing that policy
     */
    static ApprovalHandler policy(Map<Stage, String> rejects) {
        return request -> {
            String note = rejects.get(request.stage());
            return note == null
                    ? ApprovalDecision.approve("auto-policy")
                    : ApprovalDecision.reject("auto-policy", note);
        };
    }

    /**
     * Returns a handler that approves everything (used by the clean greenfield demo path).
     *
     * @return an auto-approving handler
     */
    static ApprovalHandler autoApprove() {
        return request -> ApprovalDecision.approve("auto-policy");
    }
}
