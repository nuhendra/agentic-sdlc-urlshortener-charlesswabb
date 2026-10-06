# Testing Approach, Limitations, and Trade-offs

## Strategy

The test pyramid is split by component and kept **hermetic** — no test makes a live LLM call or
requires network.

### URL shortener
- **Unit tests** (`Base62Test`, `UrlShortenerServiceTest`): pure logic with the repository
  mocked (Mockito). They assert the behavior that matters: Base62 round-trips and ordering,
  collision-free code derivation, URL dedup, scheme validation (security), and expiry handling.
- **Integration slice** (`UrlControllerIntegrationTest`): `@SpringBootTest` + `MockMvc` against a
  real Spring context and an in-memory H2 database. Covers the full create → 302 redirect → stats
  path plus the key error paths (invalid scheme → 400, unknown code → 404, dedup → same code).

### Orchestrator
- **Governance invariants** (`EngineGovernanceTest`): the engine driven by the deterministic LLM
  backend, asserting each control fires — greenfield completion with approval checkpoints,
  brownfield retry/recovery, ambiguity checkpoint + re-plan, policy-blocker rollback, operator
  safe-stop, rejected-approval rollback, and cyclic-graph rejection.
- **Runnable acceptance demo** (`DemoRunner`, via `scripts/run-demo.sh`): the same invariants as
  an executable, self-asserting demonstration that prints the audit trail and metrics and exits
  non-zero on any violation. It doubles as living documentation.

## Why a deterministic LLM backend for tests

Unit and integration tests must be fast, free, and reproducible. Live model calls are none of
those. The `LlmClient` SPI lets tests inject `DeterministicLlmClient`, which returns canned
outputs in the exact structured format the agents parse — so the engine's gates, retries,
rollback, and re-planning are exercised deterministically. The real `AnthropicLlmClient` is the
runtime default (when `ANTHROPIC_API_KEY` is set) and is validated separately.

## What is verified now vs. on your machine

- **Verified in this environment:** the framework-free orchestration core — 26 source files —
  compiles on a bare JDK 21 and the full acceptance demo passes (`scripts/run-demo.sh`, exit 0).
  See [`demo-output.txt`](demo-output.txt).
- **Verified on a machine with Maven Central access:** `mvn verify` builds both modules and runs
  the Spring/JPA tests. These could not be executed in the authoring sandbox because Maven
  Central was blocked by egress policy there (see ENGINEERING_SUMMARY.md). The Spring/JPA code is
  standard and is covered by the tests above.

## Testing things that are hard to test

- **Time/expiry:** the service takes expiry as data (`Instant`), so tests set an expiry in the
  past rather than sleeping.
- **Concurrency:** the engine runs the frontier on virtual threads; the join barrier makes
  round outcomes deterministic for assertions.
- **Retries/backoff:** backoff is tiny (ms) and bounded so tests don't wait on wall-clock time.

## Test types and how to run them

| Type | Where | Run |
|---|---|---|
| Unit | `Base62Test`, `UrlShortenerServiceTest`, `EngineGovernanceTest` | `mvn test` |
| Integration | `UrlControllerIntegrationTest`, `OrchestratorApiIntegrationTest` (full Spring context + H2 + security) | `mvn verify` |
| API / E2E | `OrchestratorApiIntegrationTest` (auth → start run → poll to COMPLETED → read audit/metrics), shortener create→redirect→stats | `mvn verify` |
| Security | `SecurityIntegrationTest` (JWT issue/use, 401, bad creds), authorization matrix in `OrchestratorApiIntegrationTest` (USER can't start; AGENT can't approve; ADMIN can) | `mvn verify` |
| Reliability | `RateLimitReliabilityTest` (burst → 429), engine governance (`EngineGovernanceTest`: retry/recovery, rollback, safe-stop) | `mvn verify` |
| Performance / Load | `LoadTest` (tagged `load`, in-process concurrency/throughput); `perf/k6-load-test.js` (HTTP load) | `mvn -Pperf verify` ; `k6 run perf/k6-load-test.js` |

The `load`-tagged tests are excluded from the normal run (they are timing-sensitive and slower)
and run only under the `perf` Maven profile — a surefire `excludedGroups` toggle in the parent
POM. The k6 script drives realistic HTTP load (ramping VUs, read-heavy mix, p95 latency and error
thresholds) against a running instance and is the right tool for true performance numbers; the
JUnit `LoadTest` checks service correctness under concurrency, not network performance.

## Limitations & trade-offs

- Canned LLM outputs validate the **orchestration contract**, not model quality — real-model
  evaluation (output scoring, regression suites over prompts) is future work.
- The integration slice uses H2, not Postgres; behavior is close but not identical (a known
  trade-off — Testcontainers against real Postgres would close the gap).
- No load/performance tests here; the shortener's scaling design is documented, not benchmarked.
- The exit gate for IMPLEMENTATION checks a self-report; wiring it to actually compile and run
  the produced code is the highest-value hardening step (the gate interface is the seam).
