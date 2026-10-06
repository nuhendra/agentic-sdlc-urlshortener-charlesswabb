package com.schwab.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for the URL shortener service.
 *
 * <p>This service is both (a) a working product in its own right and (b) the target
 * codebase that the agentic orchestrator reasons about and modifies in the brownfield
 * and ambiguous scenarios.
 */
@SpringBootApplication
@EnableAsync
public class UrlShortenerApplication {

    /**
     * Boots the URL shortener Spring Boot application.
     *
     * @param args standard JVM command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
