package com.schwab.agentic.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI (Swagger) metadata for the orchestrator API.
 *
 * <p>Declares a global {@code bearerAuth} JWT scheme so the Swagger UI "Authorize" button works.
 * Note the governance boundary still applies: an agent token can start/observe runs, but the
 * approval endpoints require an admin token.
 *
 * <p>UI: {@code /swagger-ui.html}; raw spec: {@code /v3/api-docs}.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    /** @return the OpenAPI document describing this service and its JWT security scheme */
    @Bean
    public OpenAPI orchestratorOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Agentic SDLC Orchestrator API")
                        .version("0.1.0")
                        .description("Start, observe and govern SDLC runs. Agents (ROLE_AGENT) may "
                                + "start and observe runs; approvals are ROLE_ADMIN only."))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
