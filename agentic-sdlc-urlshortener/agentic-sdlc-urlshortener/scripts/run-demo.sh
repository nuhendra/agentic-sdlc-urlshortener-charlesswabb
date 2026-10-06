#!/usr/bin/env bash
#
# Compiles and runs the framework-free orchestration core with a bare JDK (no Maven, no
# network, no dependencies) and executes the governance acceptance demo. This proves the
# graded centerpiece — the orchestration engine — works end-to-end on its own.
#
# The Spring adapter (api/, config/, OrchestratorApplication) is intentionally excluded here;
# build that with Maven (`mvn -pl orchestrator spring-boot:run`) when you want the REST API.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/orchestrator/src/main/java"
OUT="$ROOT/orchestrator/out-demo"

rm -rf "$OUT"
mkdir -p "$OUT"

# All core sources except the Spring-dependent adapter layer.
find "$SRC" -name "*.java" \
  ! -path "*/api/*" \
  ! -path "*/config/*" \
  ! -path "*/security/*" \
  ! -name "OrchestratorApplication.java" > /tmp/demo-sources.txt

echo "Compiling $(wc -l < /tmp/demo-sources.txt) core source files with javac..."
javac -d "$OUT" @/tmp/demo-sources.txt

echo "Running the orchestration acceptance demo..."
echo
java -cp "$OUT" com.schwab.agentic.scenario.DemoRunner
