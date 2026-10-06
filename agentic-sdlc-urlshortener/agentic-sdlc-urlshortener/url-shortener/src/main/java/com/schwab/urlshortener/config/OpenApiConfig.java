package com.schwab.urlshortener.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI (Swagger) metadata for the URL shortener API.
 *
 * <p>Declares a global {@code bearerAuth} security scheme so the Swagger UI "Authorize" button
 * accepts a JWT (obtained from {@code POST /auth/token}) and sends it as
 * {@code Authorization: Bearer <token>} on try-it-out calls.
 *
 * <p>UI: {@code /swagger-ui.html}; raw spec: {@code /v3/api-docs}.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    /** @return the OpenAPI document describing this service and its JWT security scheme */
    @Bean
    public OpenAPI urlShortenerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("URL Shortener API")
                        .version("0.1.0")
                        .description("Create short URLs, resolve/redirect, and read click stats. "
                                + "All /api/** endpoints require a JWT; the redirect GET /{code} is public."))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
