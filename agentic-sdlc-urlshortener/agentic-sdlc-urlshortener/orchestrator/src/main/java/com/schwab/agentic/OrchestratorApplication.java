package com.schwab.agentic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the orchestrator service. The REST layer is a thin adapter over the
 * framework-free engine in {@code com.schwab.agentic.engine} and friends.
 */
@SpringBootApplication
public class OrchestratorApplication {

    /**
     * Boots the orchestrator Spring Boot application.
     *
     * @param args standard JVM command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
