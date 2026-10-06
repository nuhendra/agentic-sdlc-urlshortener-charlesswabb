package com.schwab.agentic.api;

import com.schwab.agentic.domain.Enums.Stage;
import com.schwab.agentic.gates.ApprovalHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * Backs the human-approval checkpoint over REST: when a run reaches an approval gate, the
 * engine (running on a background thread) blocks here until an operator calls the approve or
 * reject endpoint. This is what makes autonomy "controlled" — the pipeline cannot proceed past
 * a high-impact action without a human decision.
 */
@Component
public class PendingApprovalRegistry {

    private static final Logger log = LoggerFactory.getLogger(PendingApprovalRegistry.class);

    public record Pending(String runId, Stage stage, String reason) {
    }

    private final List<Pending> pending = new CopyOnWriteArrayList<>();
    private final Map<String, SynchronousQueue<ApprovalHandler.ApprovalDecision>> waiters =
            new ConcurrentHashMap<>();

    private static String key(String runId, Stage stage) {
        return runId + "::" + stage;
    }

    /**
     * Called by the engine's approval handler; registers a pending request and blocks until an
     * operator resolves it or the timeout elapses.
     *
     * @param runId   the owning run id
     * @param stage   the stage awaiting approval
     * @param reason  why approval is required
     * @param timeout the maximum time to wait
     * @param unit    the time unit of {@code timeout}
     * @return the operator's decision, or a reject on timeout/interrupt
     */
    public ApprovalHandler.ApprovalDecision await(String runId, Stage stage, String reason,
                                                  long timeout, TimeUnit unit) {
        Pending p = new Pending(runId, stage, reason);
        pending.add(p);
        SynchronousQueue<ApprovalHandler.ApprovalDecision> q = new SynchronousQueue<>();
        waiters.put(key(runId, stage), q);
        try {
            ApprovalHandler.ApprovalDecision decision = q.poll(timeout, unit);
            if (decision == null) {
                log.warn("Approval for {} {} timed out; defaulting to reject", runId, stage);
                return ApprovalHandler.ApprovalDecision.reject("timeout", "no decision within window");
            }
            return decision;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ApprovalHandler.ApprovalDecision.reject("interrupted", "wait interrupted");
        } finally {
            pending.remove(p);
            waiters.remove(key(runId, stage));
        }
    }

    /**
     * Called by the REST endpoint to resolve a pending approval.
     *
     * @param runId    the owning run id
     * @param stage    the stage being decided
     * @param decision the operator's decision
     * @return {@code true} if a waiter received the decision; {@code false} if none was waiting
     */
    public boolean resolve(String runId, Stage stage, ApprovalHandler.ApprovalDecision decision) {
        SynchronousQueue<ApprovalHandler.ApprovalDecision> q = waiters.get(key(runId, stage));
        if (q == null) {
            return false;
        }
        try {
            return q.offer(decision, 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** @return a snapshot of all currently pending approval requests */
    public List<Pending> pending() {
        return List.copyOf(pending);
    }
}
