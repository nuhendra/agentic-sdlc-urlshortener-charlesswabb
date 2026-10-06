# Agentic SDLC System — URL Shortener

An **agentic software-engineering system** that turns a requirement into a reviewable
engineering outcome. It has two parts:

1. **`url-shortener/`** — a production-shaped Spring Boot URL shortener (core APIs, analytics,
   reliability features). This is both a working product and the codebase the orchestrator
   reasons about in the brownfield/ambiguous scenarios.
2. **`orchestrator/`** — the **agentic orchestration layer** (the centerpiece): a governed,
   stateful engine that coordinates SDLC agents — requirements → design → implementation →
   testing → documentation → release readiness — over an explicit dependency graph with
   gates, human approvals, bounded retries, rollback, safe-stop, policy guardrails, an
   audit trail, reliability metrics, and dynamic re-planning.

> **Principle:** agents execute under defined autonomy boundaries; humans own oversight,
> approvals, and final quality control.

---

## Quick start

### 1. Run the orchestration demo with zero dependencies (recommended first step)

The orchestration **core is framework-free** (pure JDK). You can compile and run the full
governance acceptance demo with nothing but a JDK 21 — no Maven, no network, no API key:

```bash
./scripts/run-demo.sh
```

This runs all three assignment scenarios plus four governance showcases (policy-triggered
rollback, operator safe-stop, rejected-approval rollback, cyclic-graph rejection), prints the
audit trail and reliability metrics for each, and asserts the governance invariants. A saved
transcript is in [`docs/demo-output.txt`](docs/demo-output.txt).

### 2. Build and test everything with Maven

Requires JDK 21 and network access to Maven Central (to download Spring Boot).

```bash
mvn -q verify            # compiles both modules and runs all tests
mvn -q -Pperf verify     # also runs the performance/load tests (JUnit tag "load")
```

Test layers (unit, integration, API/E2E, security, reliability, performance/load) and how to run
each are documented in [`docs/TESTING.md`](docs/TESTING.md). Code coverage (JaCoCo) and SonarQube
setup are in [`docs/CODE_QUALITY.md`](docs/CODE_QUALITY.md) — `mvn clean verify` writes coverage to
each module's `target/site/jacoco/`.

### Run in containers

```bash
docker compose up --build   # shortener :8080, orchestrator :8090
```

See [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) for Docker and Kubernetes guidance (and when
Kubernetes is — and isn't — worth it), and [`docs/CICD.md`](docs/CICD.md) for the Jenkins
build → test → image → push → deploy pipeline (defined in the root `Jenkinsfile`).

### 3. Run the URL shortener service

```bash
mvn -q -pl url-shortener spring-boot:run
# then:
curl -s -XPOST localhost:8080/api/v1/urls -H 'content-type: application/json' \
  -d '{"url":"https://www.schwab.com/retirement"}'
# -> 201 {"shortCode":"...","shortUrl":"http://localhost:8080/..."}
curl -s -i localhost:8080/<shortCode>          # -> 302 redirect
curl -s localhost:8080/api/v1/urls/<shortCode> # -> click stats
```

### 4. Run the orchestrator REST service

```bash
# Optional: export ANTHROPIC_API_KEY=... to use the real LLM backend (see note below).
mvn -q -pl orchestrator spring-boot:run
# start a run (auto-approval):
curl -s -XPOST 'localhost:8090/api/v1/runs/brownfield' | jq
# observe it:
curl -s localhost:8090/api/v1/runs/<runId> | jq
curl -s localhost:8090/api/v1/runs/<runId>/audit | jq
curl -s localhost:8090/api/v1/runs/<runId>/metrics | jq
# interactive human approval:
curl -s -XPOST 'localhost:8090/api/v1/runs/ambiguous?approval=manual' | jq
curl -s localhost:8090/api/v1/runs/<runId>/approvals | jq
curl -s -XPOST 'localhost:8090/api/v1/runs/<runId>/approvals/IMPLEMENTATION?decision=approve'
```

---

## Security (JWT)

Both REST APIs are secured with stateless, self-issued **HS256 JWT** authentication and
role-based authorization (Spring Security 6).

**Roles**
- `ROLE_USER` — read/create (humans).
- `ROLE_ADMIN` — sensitive operations, including **granting approvals** on the orchestrator.
- `ROLE_AGENT` — the service account for automated/machine clients: full **operational** access
  (create short URLs; start, drive and observe runs) but **not** approval granting.

**Governance tie-in:** on the orchestrator, `POST /api/v1/runs/*/approvals/**` is **ADMIN-only**, so
an agent can start and run the SDLC pipeline but cannot approve its own high-impact actions —
"controlled autonomy; humans own approvals," enforced at the HTTP layer.

**Demo credentials** (override via env vars `ADMIN_PASSWORD` / `USER_PASSWORD` / `AGENT_PASSWORD`
and `SECURITY_JWT_SECRET`): `admin/admin123`, `user/user123`, `agent/agent123`.

**Getting and using a token:**
```bash
# 1) obtain a JWT (agent service account)
TOKEN=$(curl -s -XPOST localhost:8080/auth/token \
  -H 'content-type: application/json' \
  -d '{"username":"agent","password":"agent123"}' | jq -r .token)

# 2) call a protected endpoint with it
curl -s -XPOST localhost:8080/api/v1/urls \
  -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' \
  -d '{"url":"https://www.schwab.com/retirement"}'
```
The redirect endpoint `GET /{code}` stays **public** (short links must resolve for anyone who
clicks them); `/auth/token` and `/actuator/health` are public too. The orchestrator uses the same
flow on port 8090 — an agent token can start runs, but approving a checkpoint needs an admin token.

## API docs (Swagger / OpenAPI)

Both services expose interactive OpenAPI 3 docs via springdoc:
- **Swagger UI:** `http://localhost:8080/swagger-ui.html` (shortener), `http://localhost:8090/swagger-ui.html` (orchestrator)
- **Raw spec:** `/v3/api-docs` on each.

These paths are public so the API is discoverable. To call secured endpoints from the UI, click
**Authorize**, paste a JWT obtained from `POST /auth/token`, and "try it out" — the bearer token
is sent automatically.

## Code quality & coverage (JaCoCo + SonarQube)

**JaCoCo** measures test coverage; **SonarQube** displays it alongside bugs, code smells, and
security hotspots. JaCoCo is wired into the build — no server needed:

```bash
mvn clean verify
# HTML report:  <module>/target/site/jacoco/index.html
# XML (Sonar):  <module>/target/site/jacoco/jacoco.xml
```

To publish to SonarQube:

```bash
docker run -d --name sonarqube -p 9000:9000 sonarqube:lts-community   # http://localhost:9000
mvn clean verify
mvn sonar:sonar -Dsonar.host.url=http://localhost:9000 -Dsonar.token=YOUR_TOKEN
```

The parent POM points Sonar at the JaCoCo XML, sets the Java level, and excludes the runnable
entry points. The Jenkins pipeline has an optional `RUN_SONAR` stage. Full details — including the
JDK-27 note — are in [`docs/CODE_QUALITY.md`](docs/CODE_QUALITY.md).

## Real LLM backend

The agents call a real LLM by default when `ANTHROPIC_API_KEY` is set (Anthropic Messages API,
via the dependency-free `AnthropicLlmClient`). When the key is absent, the service and the demo
fall back to a hermetic `DeterministicLlmClient` so everything still runs offline and tests stay
fast and reproducible — you never want unit tests making live, paid, non-deterministic calls.

---

## Repository layout

```
agentic-sdlc-urlshortener/
├── pom.xml                      # parent (multi-module)
├── scripts/run-demo.sh          # zero-dependency orchestration demo
├── url-shortener/               # Spring Boot URL shortener (the product)
│   └── src/main/java/com/schwab/urlshortener/
│       ├── controller/          # HTTP endpoints (UrlController)
│       ├── service/             # business logic (UrlShortenerService, UrlValidator, AnalyticsService)
│       ├── repository/          # JPA repositories
│       ├── model/               # JPA entities (UrlMapping, ClickEvent)
│       ├── dto/                 # request/response records
│       ├── exception/           # custom exceptions + global handler
│       ├── util/                # stateless helpers (Base62)
│       └── config/              # beans + RateLimitFilter
├── orchestrator/                # the agentic orchestration layer
│   └── src/main/java/com/schwab/agentic/
│       ├── domain/              # Stage graph, StageNode, RunContext, Artifact, Decision  (pure JDK)
│       ├── engine/              # OrchestrationEngine, RetryPolicy, SafeStop, RunConfig   (pure JDK)
│       ├── agents/              # Agent SPI + the six SDLC agents                         (pure JDK)
│       ├── llm/                 # LlmClient SPI, AnthropicLlmClient, DeterministicLlmClient (pure JDK)
│       ├── gates/               # entry/exit/approval gates                               (pure JDK)
│       ├── policy/              # PolicyEngine + security/change-control rules            (pure JDK)
│       ├── audit/               # append-only AuditLog                                    (pure JDK)
│       ├── metrics/             # RunMetrics + MetricsRegistry                            (pure JDK)
│       ├── scenario/            # ScenarioLibrary + DemoRunner                            (pure JDK)
│       ├── api/                 # REST adapter                                            (Spring)
│       └── config/              # Spring wiring                                           (Spring)
└── docs/
    ├── ARCHITECTURE.md          # components, orchestration model, control flow, decisions
    ├── SCENARIOS.md             # greenfield, brownfield, ambiguous + governance showcases
    ├── TESTING.md               # testing approach, limitations, trade-offs
    ├── ENGINEERING_SUMMARY.md   # plan/rationale, risks, assumptions, limitations
    └── demo-output.txt          # saved transcript of scripts/run-demo.sh
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the design and
[`docs/ENGINEERING_SUMMARY.md`](docs/ENGINEERING_SUMMARY.md) for the plan, risks, and
assumptions (including a note on the build environment).
