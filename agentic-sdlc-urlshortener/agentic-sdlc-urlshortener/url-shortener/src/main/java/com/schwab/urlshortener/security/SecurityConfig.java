package com.schwab.urlshortener.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security.
 *
 * <p>Authorization matrix:
 * <ul>
 *   <li>{@code POST /auth/token}, {@code /actuator/health}, the H2 console — public.</li>
 *   <li>{@code GET /{code}} (the redirect) — public, because short links must resolve for
 *       anyone who clicks them.</li>
 *   <li>{@code /api/v1/urls/**} (create, stats) — any of ROLE_USER, ROLE_AGENT, ROLE_ADMIN.</li>
 *   <li>everything else — authenticated.</li>
 * </ul>
 * The {@code agent} service account (ROLE_AGENT) therefore has full operational access.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    /** @return the BCrypt password encoder used to store and check passwords */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Builds the stateless security filter chain.
     *
     * @param http      the Spring Security HTTP builder
     * @param jwtFilter the bearer-token authentication filter
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if the chain cannot be built
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.frameOptions(fo -> fo.sameOrigin())) // allow the H2 console frame
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/**", "/actuator/health", "/h2-console/**").permitAll()
                        // OpenAPI spec + Swagger UI are public so the API is discoverable.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/urls/**").hasAnyRole("USER", "AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/{code}").permitAll()
                        .anyRequest().authenticated())
                // Return 401 (not the default 403) when no/!valid credentials are presented.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        (request, response, authEx) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
