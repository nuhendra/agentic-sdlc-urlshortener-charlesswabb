package com.schwab.agentic.api;

import com.schwab.agentic.audit.AuditLog;
import com.schwab.agentic.domain.Enums.Stage;
import com.schwab.agentic.domain.Run;
import com.schwab.agentic.domain.StageNode;
import com.schwab.agentic.engine.OrchestrationEngine;
import com.schwab.agentic.engine.RunConfig;
import com.schwab.agentic.gates.ApprovalHandler;
import com.schwab.agentic.metrics.MetricsRegistry;
import com.schwab.agentic.metrics.RunMetrics;
import com.schwab.agentic.scenario.ScenarioLibrary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * REST API (v1) for driving and observing orchestration runs. All paths are under {@code /api/v1}.
 *
 * <pre>
 *   POST /api/v1/runs/{scenario}?approval=auto|manual   start greenfield|brownfield|ambiguous|governance
 *   GET  /api/v1/runs/{id}                               run + per-stage state
 *   GET  /api/v1/runs/{id}/audit                         full audit trail (traceability)
 *   GET  /api/v1/runs/{id}/metrics                       reliability metrics for the run
 *   GET  /api/v1/runs/{id}/approvals                     pending human checkpoints
 *   POST /api/v1/runs/{id}/approvals/{stage}?decision=approve|reject
 *   GET  /api/v1/metrics                                 fleet reliability metrics
 * </pre>
 */
@RestController
@RequestMapping("/api/v1")
public class OrchestratorController {

    private final OrchestrationEngine engine;
    private final AuditLog audit;
    private final MetricsRegistry metrics;
    private final RunRegistry runs;
    private final PendingApprovalRegistry approvals;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Constructs the controller.
     *
     * @param engine    the orchestration engine
     * @param audit     the audit log
     * @param metrics   the metrics registry
     * @param runs      the run registry
     * @param approvals the pending-approval registry
     */
    public OrchestratorController(OrchestrationEngine engine, AuditLog audit,
                                  MetricsRegistry metrics, RunRegistry runs,
                                  PendingApprovalRegistry approvals) {
        this.engine = engine;
        this.audit = audit;
        this.metrics = metrics;
        this.runs = runs;
        this.approvals = approvals;
    }

    /**
     * Starts a scenario run on a background thread and returns immediately.
     *
     * @param scenario one of {@code greenfield|brownfield|ambiguous|governance}
     * @param approval {@code auto} to auto-approve, or {@code manual} for human checkpoints
     * @return 202 Accepted with the run id and status URL, or 400 for an unknown scenario
     */
    @PostMapping("/runs/{scenario}")
    public ResponseEntity<Map<String, Object>> start(@PathVariable("scenario") String scenario,
                                                     @RequestParam(name = "approval", defaultValue = "auto") String approval) {
        Run run = switch (scenario.toLowerCase()) {
            case "greenfield" -> ScenarioLibrary.greenfield();
            case "brownfield" -> ScenarioLibrary.brownfield();
            case "ambiguous" -> ScenarioLibrary.ambiguous();
            case "governance" -> ScenarioLibrary.governanceFault();
            default -> null;
        };
        if (run == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "unknown scenario; use greenfield|brownfield|ambiguous|governance"));
        }
        runs.put(run);

        ApprovalHandler handler = "manual".equalsIgnoreCase(approval)
                ? req -> approvals.await(req.runId(), req.stage(), req.reason(), 10, TimeUnit.MINUTES)
                : ApprovalHandler.autoApprove();
        RunConfig config = RunConfig.defaults().withApproval(handler);

        // Execute off the request thread so manual-approval runs can pause without blocking HTTP.
        executor.submit(() -> engine.execute(run, config));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", run.id());
        body.put("scenario", run.requirement().scenarioType());
        body.put("approvalMode", approval);
        body.put("statusUrl", "/api/v1/runs/" + run.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    /**
     * Returns a run's lifecycle state and per-stage states.
     *
     * @param id the run id
     * @return 200 OK with the state, or 404 if the run is unknown
     */
    @GetMapping("/runs/{id}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable("id") String id) {
        Run run = runs.get(id);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, String> stages = new LinkedHashMap<>();
        for (StageNode n : run.graph().nodes()) {
            stages.put(n.stage().name(), n.state().name());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", run.id());
        body.put("title", run.requirement().title());
        body.put("state", run.state().name());
        body.put("stages", stages);
        return ResponseEntity.ok(body);
    }

    /**
     * Returns the full audit trail for a run.
     *
     * @param id the run id
     * @return 200 OK with the ordered audit events, or 404 if the run is unknown
     */
    @GetMapping("/runs/{id}/audit")
    public ResponseEntity<Object> auditTrail(@PathVariable("id") String id) {
        if (runs.get(id) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(audit.eventsForRun(id));
    }

    /**
     * Returns the reliability metrics for a run.
     *
     * @param id the run id
     * @return 200 OK with the metrics, or 404 if the run is unknown
     */
    @GetMapping("/runs/{id}/metrics")
    public ResponseEntity<Map<String, Object>> runMetrics(@PathVariable("id") String id) {
        RunMetrics m = metrics.get(id);
        if (m == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("successRate", m.successRate());
        body.put("stagesSucceeded", m.stagesSucceeded());
        body.put("stagesFailed", m.stagesFailed());
        body.put("retries", m.retries());
        body.put("rollbacks", m.rollbacks());
        body.put("approvals", m.approvals());
        body.put("replans", m.replans());
        body.put("mttrMillis", m.mttrMillis());
        body.put("endToEndLatencyMillis", m.endToEndLatencyMillis());
        body.put("llmCalls", m.llmCalls());
        body.put("llmTokens", m.llmTokens());
        return ResponseEntity.ok(body);
    }

    /**
     * Lists the pending approval checkpoints for a run.
     *
     * @param id the run id
     * @return the pending approvals awaiting a decision (possibly empty)
     */
    @GetMapping("/runs/{id}/approvals")
    public Object pendingApprovals(@PathVariable("id") String id) {
        return approvals.pending().stream().filter(p -> p.runId().equals(id)).toList();
    }

    /**
     * Resolves a pending approval checkpoint.
     *
     * @param id       the run id
     * @param stage    the stage name awaiting approval
     * @param decision {@code approve} or anything else (treated as reject)
     * @return 200 OK indicating whether a waiter was resolved, or 400 for an unknown stage
     */
    @PostMapping("/runs/{id}/approvals/{stage}")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable("id") String id,
                                                      @PathVariable("stage") String stage,
                                                      @RequestParam("decision") String decision) {
        Stage s;
        try {
            s = Stage.valueOf(stage.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown stage: " + stage));
        }
        ApprovalHandler.ApprovalDecision d = "approve".equalsIgnoreCase(decision)
                ? ApprovalHandler.ApprovalDecision.approve("operator")
                : ApprovalHandler.ApprovalDecision.reject("operator", "rejected via API");
        boolean resolved = approvals.resolve(id, s, d);
        return ResponseEntity.ok(Map.of("resolved", resolved, "stage", s.name(), "decision", decision));
    }

    /**
     * Returns the fleet-wide reliability KPIs aggregated across all runs.
     *
     * @return the aggregated metrics
     */
    @GetMapping("/metrics")
    public Map<String, Object> fleetMetrics() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", metrics.render());
        body.put("fleetSuccessRate", metrics.fleetSuccessRate());
        body.put("avgRetriesPerRun", metrics.avgRetriesPerRun());
        body.put("rollbackFrequency", metrics.rollbackFrequency());
        body.put("avgMttrMillis", metrics.avgMttrMillis());
        body.put("avgEndToEndLatencyMillis", metrics.avgEndToEndLatencyMillis());
        return body;
    }
}
