# Multi-stage build for the agentic orchestrator service.
# Build context must be the repository root:  docker build -f orchestrator/Dockerfile -t orchestrator:latest .

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -q -B -pl orchestrator -am -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 1001 appuser
COPY --from=build /workspace/orchestrator/target/orchestrator.jar /app/app.jar
USER 1001
EXPOSE 8090
# ANTHROPIC_API_KEY (optional) selects the real LLM backend; without it the deterministic
# backend is used so the service still runs.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
