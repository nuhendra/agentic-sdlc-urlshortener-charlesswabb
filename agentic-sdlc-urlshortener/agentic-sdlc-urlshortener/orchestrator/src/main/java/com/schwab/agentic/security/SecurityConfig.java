package com.schwab.agentic.security;

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
 * Stateless JWT security for the orchestrator, with an authorization matrix that encodes the
 * project's governance principle at the HTTP layer:
 *
 * <ul>
 *   <li>{@code POST /auth/token}, {@code /actuator/health} — public.</li>
 *   <li>{@code POST /api/v1/runs/*&#47;approvals/**} (grant/deny an approval) — <b>ROLE_ADMIN only</b>.
 *       Humans own approvals: an agent can drive a run but cannot approve its own high-impact
 *       actions.</li>
 *   <li>{@code POST /api/v1/runs/*} (start a run) — ROLE_AGENT or ROLE_ADMIN.</li>
 *   <li>{@code GET /api/v1/runs/**}, {@code GET /api/v1/metrics} (observe) — USER, AGENT, or ADMIN.</li>
 *   <li>everything else — authenticated.</li>
 * </ul>
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
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/**", "/actuator/health").permitAll()
                        // OpenAPI spec + Swagger UI are public so the API is discoverable.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Humans own approvals — agents cannot self-approve.
                        .requestMatchers(HttpMethod.POST, "/api/v1/runs/*/approvals/**").hasRole("ADMIN")
                        // Starting/driving runs is operational — agents and admins.
                        .requestMatchers(HttpMethod.POST, "/api/v1/runs/*").hasAnyRole("AGENT", "ADMIN")
                        // Observing is open to any authenticated role.
                        .requestMatchers(HttpMethod.GET, "/api/v1/runs/**", "/api/v1/metrics")
                        .hasAnyRole("USER", "AGENT", "ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        (request, response, authEx) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
