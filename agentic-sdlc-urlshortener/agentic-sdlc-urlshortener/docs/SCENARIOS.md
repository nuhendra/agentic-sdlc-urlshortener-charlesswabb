# Scenarios

Each scenario shows **decomposition** (how the requirement becomes a governed task graph),
**orchestration** (how the engine executes it, including the non-linear parts), and
**validation** (the gates/controls that make the outcome trustworthy). All are runnable via
`scripts/run-demo.sh`; the full transcript is in [`demo-output.txt`](demo-output.txt).

The task graph is the same shape in every scenario — what differs is the requirement, the
risk/approval configuration, and (for the governance cases) injected faults:

```
REQUIREMENTS → DESIGN → IMPLEMENTATION → { TESTING ∥ DOCUMENTATION } → RELEASE_READINESS
```

---

## Scenario 1 — Greenfield: add custom alias support

**Requirement:** "Allow API callers to supply a custom alias when shortening a URL."

**Decomposition.** New, well-defined feature. Requirements (low ambiguity) → design the request
schema and collision rule → implement → test ∥ document → release.

**Orchestration.** Clean path. Demonstrates:
- the **parallel fan-out** — TESTING and DOCUMENTATION both become ready once IMPLEMENTATION
  succeeds and run concurrently;
- the **synchronization join** — RELEASE_READINESS only starts after both finish;
- **two approval checkpoints** — IMPLEMENTATION (code change) and RELEASE_READINESS (go/no-go).

**Validation.** All exit gates pass; policy clean; both approvals granted. Result: `COMPLETED`,
`success_rate=100%`, `retries=0`, `approvals=2`.

---

## Scenario 2 — Brownfield: harden redirect + rate-limit the resolve path

**Requirement:** "Harden `GET /{code}` against open-redirect and add per-client rate limiting to
the resolve path, without breaking existing behavior."

**Decomposition & codebase reasoning.** The requirement names a `targetModule`
(`url-shortener/controller.UrlController`). The design stage identifies the impacted modules
(`controller.UrlController`, `service.UrlShortenerService`, `service.UrlValidator`) and constrains the change to be additive
and backward-compatible (preserve 302 + analytics).

**Orchestration.** Demonstrates **bounded retry with self-correction**: the implementation's
first attempt is incomplete (the open-redirect guard is missing), so the **exit gate rejects it**;
the engine retries, feeding the failure reason back into the agent, and the second attempt
passes.

```
#63 AGENT_CALL       IMPLEMENTATION  attempt 1 produced implementation-output
#64 EXIT_GATE        IMPLEMENTATION  exit gate: implementation self-check not complete
#65 STAGE_FAILED     IMPLEMENTATION  attempt 1 failed
#66 STAGE_RETRY      IMPLEMENTATION  retrying (self-correction)
#67 AGENT_CALL       IMPLEMENTATION  attempt 2 produced implementation-output
#68 EXIT_GATE        IMPLEMENTATION  ok
```

**Validation.** Recovered within the retry budget. Result: `COMPLETED`, `retries=1`, and MTTR is
recorded (time from the first failure to recovery).

---

## Scenario 3 — Ambiguous: "make it more reliable"

**Requirement:** "Make the URL shortener more reliable." (Deliberately underspecified.)

**Decomposition & ambiguity handling.** The RequirementsAnalystAgent scores ambiguity high
(0.80) and emits open questions (which reliability dimension? what target SLO? multi-instance?).
Because the requirements stage is configured HIGH-risk + approval-required for this scenario, the
engine raises a **human clarification checkpoint** before any design work proceeds.

**Orchestration — dynamic re-planning.** After the run completes against the clarified spec, the
demo simulates a *further* clarification and calls `engine.replan(run, REQUIREMENTS)`. The engine
invalidates REQUIREMENTS' transitive dependents (DESIGN, IMPLEMENTATION, TESTING, DOCUMENTATION,
RELEASE_READINESS) and re-executes them against the new context:

```
#139 REPLAN  REQUIREMENTS  upstream changed; invalidating dependents [TESTING, DOCUMENTATION, DESIGN, IMPLEMENTATION, RELEASE_READINESS]
#142 STAGE_READY DESIGN ...   (re-derivation begins)
```

**Validation.** `approvals≥1` (the ambiguity checkpoint fired), `replans=1`, and the run
re-completes. This is the non-linear, stateful behavior the assignment asks for — not a one-shot
pipeline.

---

## Governance showcases (reliability & risk controls)

These prove the safety machinery actually fires.

### A. Policy BLOCKER → rollback (fault-injected secret leak)
Implementation output contains a hardcoded `sk-live-...` secret. It passes the exit gate but the
**policy gate** catches it (`SEC-001-no-hardcoded-secrets`), fails the stage, retries to the
budget, then the run **rolls back** — compensating the already-succeeded REQUIREMENTS and DESIGN
stages in reverse order. Result: `ROLLED_BACK`, `rollbacks>0`.

### B. Operator safe-stop
An operator "stops before IMPLEMENTATION". The engine trips safe-stop, preserves state, rolls
back completed stages, and ends `STOPPED` — implementation never runs.

### C. Rejected approval → rollback
The RELEASE_READINESS approval is rejected. The stage fails and the whole run rolls back
(all five prior stages compensated). Result: `ROLLED_BACK`.

### D. Cyclic graph rejected at entry
A graph with DESIGN⇄IMPLEMENTATION is rejected by `validateAcyclic()` before any stage runs —
the whole-run entry gate.

---

## Fleet reliability metrics (end of demo)

```
runs=6 fleet_success_rate≈85% avg_retries≈0.5 rollback_freq≈50% avg_mttr_ms≈2 avg_e2e_ms≈20
```

(The rollback frequency is high *by construction* here because half the demo runs are
deliberately fault-injected governance showcases.)
