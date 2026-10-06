# CI/CD with Jenkins + Docker + Kubernetes

Automated pipeline that builds, tests, containerizes, and deploys both services.

```
 Developer push ──► Git ──► Jenkins (webhook/poll) ──► Build & Test ──► Docker build+push ──► kubectl deploy ──► Kubernetes
```

The pipeline is defined in the repository root [`Jenkinsfile`](../Jenkinsfile) (pipeline-as-code,
so it is versioned and reviewed like any other source).

## Step 1 — Set up Jenkins

1. **Install Jenkins** on a server (or run the `jenkins/jenkins:lts` container). The build node
   must also have the **Docker CLI** and **kubectl** on its PATH.
2. **Install plugins:** Git, Pipeline, Docker Pipeline, Kubernetes CLI (and Credentials).
3. **Global Tool Configuration** → add:
   - a JDK named **`JDK21`** (Java 21 — the version the project targets),
   - a Maven named **`Maven3`**.
4. **Add credentials** (Manage Jenkins → Credentials):
   - **`registry-credentials`** — username/password for your container registry.
   - **`kubeconfig`** — a *Secret file* credential containing the target cluster's kubeconfig.
   - Git credentials if the repo is private (used by the job's SCM config).

## Step 2 — Create the pipeline job

- New Item → **Multibranch Pipeline** (or a Pipeline job) → point it at this Git repo.
- It will discover the root `Jenkinsfile` automatically.
- Add a webhook from your Git host to Jenkins (or enable SCM polling) so a push triggers a build.

## Pipeline stages (what the Jenkinsfile does)

| Stage | Action |
|---|---|
| **Checkout** | Checks out the commit; derives an immutable image tag `‹short-sha›-‹build#›`. |
| **Build & Test** | `mvn clean verify` — compile + unit, integration, API/E2E, security, reliability tests. Publishes JUnit reports and archives the jars. |
| **Performance Tests** | Optional (`RUN_PERF_TESTS` param) — runs the `load`-tagged tests via `mvn -Pperf`. |
| **Build & Push Images** | Builds `url-shortener` and `orchestrator` images from their Dockerfiles and pushes `‹tag›` + `latest` to the registry (Docker Pipeline plugin). |
| **Deploy to Kubernetes** | `kubectl apply -f deploy/k8s/`, then `kubectl set image` to the new immutable tag, then `kubectl rollout status`. On failure it `rollout undo`s (automatic rollback). |

## Parameters

- `REGISTRY` — registry/namespace to push to (e.g. `docker.io/your-org`).
- `K8S_NAMESPACE` — namespace to deploy into.
- `RUN_PERF_TESTS` — also run the load tests.
- `DEPLOY` — deploy after a successful build (turn off for build-only branches).

## Design notes (and how they map to good CI/CD practice)

- **Immutable tags.** Images are tagged with the commit SHA + build number, not just `latest`, so
  every deploy is traceable to a commit and rollbacks are deterministic. `latest` is pushed too
  for convenience only.
- **Quality gate before packaging.** Images are built *after* `mvn verify` passes, so a failing
  test never produces a deployable image.
- **Safe deploys.** `rollout status` turns a stuck deploy into a failed build; the `failure` post
  step runs `rollout undo` to restore the previous version.
- **Fits the app's constraints.** The shortener scales horizontally (HPA in the manifests); the
  orchestrator is deployed as a single replica until its in-memory state is externalized (see
  DEPLOYMENT.md). The pipeline doesn't paper over that — it deploys what each service supports today.

## Hardening for real production (next steps)

- Build images rootlessly in-cluster with **Kaniko** (via the Kubernetes plugin's pod agents)
  instead of a Docker daemon on the Jenkins node.
- Scan images (Trivy/Grype) and run SCA/SAST as gates before push.
- Promote one immutable image through environments (dev → staging → prod) with manual approval
  gates — mirroring the orchestrator's own "human approval" principle at the delivery layer.
- Replace `kubectl set image` with GitOps (Argo CD/Flux): the pipeline updates a manifest repo and
  the cluster reconciles, giving you audit and drift detection for free.
