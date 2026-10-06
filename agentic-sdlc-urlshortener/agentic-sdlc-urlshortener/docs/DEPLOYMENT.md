# Deployment: Docker & Kubernetes

## Should you containerize this? (recommendation)

**Docker — yes, do it.** It's low-effort, high-value here:
- **Reproducible runtime.** The image pins a **Java 21** JRE regardless of the host JDK — which
  also removes the "JDK is newer than the tooling" warnings you saw running on JDK 27.
- **Ship/run anywhere** with one command; no local Maven/JDK needed to *run* the services.
- **Clean service boundary.** The two apps already deploy independently; one image each makes
  that explicit. Multi-stage builds keep the images small (build in a Maven image, run on a JRE).

Dockerfiles (`url-shortener/Dockerfile`, `orchestrator/Dockerfile`) and a `docker-compose.yml`
are included.

**Kubernetes — advisable only when you have the operational need.** Reach for it when you need
horizontal autoscaling, self-healing, rolling/zero-downtime deploys, and service discovery across
many services — i.e. real production traffic. For an interview prototype or a single-box demo,
K8s is usually **overkill**, and Docker Compose is the right tool. Manifests are included under
`deploy/k8s/` so the path is clear, but note two app-specific caveats before going there:

- **The shortener is stateless and scales well** — the manifest runs 2 replicas with an HPA. But
  multi-instance correctness needs the shared infrastructure already called out in
  ARCHITECTURE.md: a shared cache and rate-limit store (Redis) and a distributed id allocator
  instead of the single DB sequence. Until then, multiple replicas each have their own in-memory
  cache/limiter and local id derivation.
- **The orchestrator keeps run/audit/metrics state in memory** (prototype limitation), so it runs
  as a **single replica**. Scaling it out requires externalizing that state (DB/event store) and
  making runs resumable first.

**Bottom line:** containerize now (Docker + Compose); adopt Kubernetes when you move toward
production scale, and do the "externalize state" work first so both services are truly
horizontally scalable.

## Docker

```bash
# Build each image (context = repo root)
docker build -f url-shortener/Dockerfile -t url-shortener:latest .
docker build -f orchestrator/Dockerfile  -t orchestrator:latest  .

# Or run both together
docker compose up --build
# shortener -> http://localhost:8080 , orchestrator -> http://localhost:8090
```

Both services read secrets from env vars (`SECURITY_JWT_SECRET`, `ADMIN_PASSWORD`,
`AGENT_PASSWORD`, and optionally `ANTHROPIC_API_KEY`). Compose wires the same JWT secret into
both so an agent token works across services.

## Kubernetes

```bash
kubectl apply -f deploy/k8s/secret.yaml
kubectl apply -f deploy/k8s/url-shortener.yaml
kubectl apply -f deploy/k8s/orchestrator.yaml
```

The manifests include readiness/liveness probes (on the public `/actuator/health`), resource
requests/limits, a `Service` each, and an HPA for the shortener. For real use you would add an
Ingress (or Gateway API) for external traffic and TLS, and replace the demo `Secret` with a
sealed/external secret. Images must be pushed to a registry your cluster can pull from (swap the
`image:` references).
