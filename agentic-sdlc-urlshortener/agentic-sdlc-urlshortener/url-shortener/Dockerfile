# Multi-stage build for the URL shortener service.
# Build context must be the repository root:  docker build -f url-shortener/Dockerfile -t url-shortener:latest .

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
# Build only this module and what it needs (-am), skipping tests for the image build.
RUN mvn -q -B -pl url-shortener -am -DskipTests package

# ---- runtime stage ----
# Pinned to a Java 21 JRE (the version this project targets), independent of the host JDK.
FROM eclipse-temurin:21-jre
WORKDIR /app
# Run as a non-root user.
RUN useradd -r -u 1001 appuser
COPY --from=build /workspace/url-shortener/target/url-shortener.jar /app/app.jar
USER 1001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
