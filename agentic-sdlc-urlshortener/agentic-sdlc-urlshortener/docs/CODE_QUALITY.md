# Code Coverage & Quality (JaCoCo + SonarQube)

**JaCoCo** measures test coverage; **SonarQube** displays that coverage alongside bugs, code
smells, security hotspots, and duplication. The pipeline is: JaCoCo produces a report → SonarQube
ingests it.

## Coverage (JaCoCo) — no server needed

JaCoCo is wired into the build (parent `pom.xml`): the agent instruments the tests and a report
is written during `verify`.

```bash
mvn clean verify
```

Per-module HTML and XML reports land in:
```
url-shortener/target/site/jacoco/index.html      # open in a browser
url-shortener/target/site/jacoco/jacoco.xml       # consumed by SonarQube
orchestrator/target/site/jacoco/index.html
orchestrator/target/site/jacoco/jacoco.xml
```

Add `-Pperf` to include the load tests in the covered run.

## SonarQube

### 1. Run a SonarQube server (local, via Docker)

```bash
docker run -d --name sonarqube -p 9000:9000 sonarqube:lts-community
# open http://localhost:9000  (default login admin/admin, then change the password)
# create a token: My Account -> Security -> Generate Token
```

### 2. Analyze and publish

```bash
mvn clean verify                      # generates coverage first
mvn sonar:sonar \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.token=YOUR_TOKEN
# results appear in the SonarQube dashboard at http://localhost:9000
```

The parent POM already points Sonar at the JaCoCo XML
(`sonar.coverage.jacoco.xmlReportPaths`), sets the Java source level, and excludes the runnable
entry points (`DemoRunner`, `*Application`) from coverage.

### In CI (Jenkins)

The `Jenkinsfile` has an optional **Code Quality (SonarQube)** stage (enable the `RUN_SONAR`
parameter). It uses the SonarQube Scanner for Jenkins plugin: configure your server under
*Manage Jenkins → System → SonarQube servers* with the name **`SonarQube`**, and the stage runs
`mvn sonar:sonar` inside `withSonarQubeEnv('SonarQube')`. A quality-gate gate can be enforced by
uncommenting the `waitForQualityGate` line (needs a SonarQube webhook back to Jenkins).

## Note on JDK 27

JaCoCo 0.8.12 targets Java class files up to ~Java 23. Because this project compiles with
`--release 21`, the *bytecode* is Java 21 and JaCoCo instruments it fine. If you run the build on
a JDK newer than JaCoCo supports and hit an "unsupported class file major version" error, run the
build on **JDK 21** (the version the project targets) — the recommended setup anyway.
