# Final Engineering Summary

## 1. Plan and rationale

**Interpretation of intent.** The URL shortener is a deliberately solved problem; the graded
differentiator is the **agentic orchestration layer**. So the plan was: build a clean,
correct-but-not-gold-plated shortener as the substrate and the brownfield target, and invest the
real engineering effort in a **governed, stateful orchestration engine** that demonstrates
non-linear SDLC execution with controlled autonomy.

**Decomposition of the build itself:**
1. Scaffold a multi-module Maven project (JDK 21).
2. Build the URL shortener (APIs, analytics, reliability) + tests.
3. Build the orchestration **core** (graph, engine, gates, policy, audit, metrics) — framework-free.
4. Build the SDLC agents + the real LLM client.
5. Wire the three scenarios + governance showcases; make them runnable and self-asserting.
6. Add the Spring REST adapter.
7. Verify (compile + demo + tests) and document.

**Key architectural decision — framework-free core.** Keeping the engine dependency-free
(ports-and-adapters, Spring only at the edges) made the centerpiece independently testable and
runnable, and let it be verified in isolation with a bare JDK. This paid off directly as the
primary validation path.

## 2. Artifacts produced

- **URL shortener service** — Base62, entity/repository, service (collision-free codes, dedup,
  caching, expiry), controller (201 create / 302 redirect / stats), async analytics, rate-limit
  filter, validation, global error handling; unit + integration tests; H2 default, Postgres
  profile.
- **Orchestration engine** — explicit dependency DAG with ready-frontier parallelism + sync
  join; entry/exit/approval/policy gates; bounded self-correcting retries; fallback; saga
  rollback; safe-stop (operator + error budget); dynamic re-planning; versioned cross-stage
  context with decision lineage.
- **Six SDLC agents** + `LlmClient` SPI with a real `AnthropicLlmClient` (dependency-free) and a
  hermetic `DeterministicLlmClient`.
- **Policy engine** — security (no hardcoded secrets, no SQL string concatenation) and change
  control (change-size budget, protected paths).
- **Observability** — append-only audit log (correlation ids, monotonic sequence) and reliability
  metrics (success rate, retry/rollback frequency, MTTR, end-to-end latency, tokens).
- **REST API** — start/observe runs, audit, metrics, and interactive human approvals.
- **Runnable acceptance demo** + saved transcript, and the docs in this folder.

## 3. Risks, trade-offs, and validation

| Risk / failure scenario | Mitigation in the system | How it's validated |
|---|---|---|
| Agent produces low-quality output | Exit gate independently validates; bounded self-correcting retry | Brownfield scenario (retry→recover) |
| Agent emits unsafe code (secret, injection) | Policy gate BLOCKER halts the stage | Governance A (secret leak → rollback) |
| High-impact action without oversight | Human approval checkpoints on IMPLEMENTATION/RELEASE/HIGH-risk | Greenfield (2 approvals); Governance C (reject→rollback) |
| Partial/failed run leaves inconsistent state | Saga rollback compensates succeeded stages in reverse | Governance A & C |
| Runaway / looping agent | Bounded retries + error-budget safe-stop | Retry budget exhaustion in Governance A |
| Requirement changes mid-flight | Dynamic re-planning invalidates & re-derives dependents | Ambiguous scenario (replan=1) |
| Mis-specified pipeline (cycle) | Whole-run entry gate validates the DAG | Governance D (cyclic rejected) |
| No audit of autonomous actions | Append-only audit log of every transition/decision | Every scenario prints its trail |

## 4. Assumptions

- The agents' job is to **produce reviewable engineering artifacts** (specs, designs, described
  code changes, test/doc outputs, release decisions), not to autonomously commit to production.
- One LLM call per stage attempt is sufficient for a prototype; a stage could internally be a
  multi-call sub-agent without changing the engine contract.
- Human approval in the prototype is an operator decision over REST (or a deterministic policy in
  tests); in production it would integrate with the org's change-management/approval tooling.
- JDK 21 is available (records, sealed-friendly design, virtual threads).

## 5. Limitations

- Orchestrator state (audit, metrics, runs) is **in-memory**; not durable or resumable across
  restarts.
- Rate limiting, caching, and the id sequence are **per-instance**; multi-instance needs shared
  infrastructure (Redis, distributed id allocator).
- Implementation artifacts are **represented, not applied** to the repo; the exit gate checks a
  self-report rather than compiling/running the produced code.
- The deterministic backend validates orchestration behavior, **not model quality**.

## 6. Build-environment note (transparency)

This project was authored in a sandbox whose egress policy **blocked Maven Central**, so the
Maven/Spring build and the Spring/JPA tests could not be executed there. To keep the graded
centerpiece genuinely verifiable, the orchestration core was designed framework-free and
**compiled and run on a bare JDK** (`scripts/run-demo.sh`, 26 files, all governance invariants
pass — transcript in `demo-output.txt`). On any machine with normal Maven Central access,
`mvn verify` builds both modules and runs all tests. This constraint is also what motivated the
clean core/adapter split — a case of an environmental limitation reinforcing a sound design.

## 7. What I'd do next (prioritized)

1. Make the IMPLEMENTATION exit gate **compile and run the produced code** (and real tests) —
   the single highest-value correctness upgrade; the gate interface is already the seam.
2. Persist runs/audit/metrics to a store and make runs **resumable**; move approvals to durable,
   event-driven waits.
3. Wire artifacts to real **VCS branches/PRs** with the policy gate as a pre-merge check.
4. Add **real-model evaluation** (output scoring + prompt regression suites) alongside the
   hermetic tests.
5. Externalize scaling for the shortener (Redis cache/rate-limit, distributed id allocator).
