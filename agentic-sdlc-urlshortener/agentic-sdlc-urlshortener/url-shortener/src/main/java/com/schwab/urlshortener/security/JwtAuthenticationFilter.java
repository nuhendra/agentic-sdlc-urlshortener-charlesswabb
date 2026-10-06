package com.schwab.urlshortener.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads a {@code Authorization: Bearer <jwt>} header, verifies it, and populates the Spring
 * Security context with the principal and its authorities. Requests without a (valid) token
 * simply pass through unauthenticated; authorization rules then decide whether that is allowed.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;

    /**
     * @param jwtService the token verifier
     */
    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * Authenticates the request from its bearer token, if present and valid.
     *
     * @param request  the current request
     * @param response the current response
     * @param chain    the remaining filter chain
     * @throws ServletException if the downstream chain fails
     * @throws IOException      if I/O fails
     */
    @Override
    @SuppressWarnings("unchecked")
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                try {
                    Claims claims = jwtService.parse(header.substring(7));
                    List<String> roles = claims.get("roles", List.class);
                    var authorities = (roles == null ? List.<String>of() : roles).stream()
                            .map(SimpleGrantedAuthority::new)
                            .toList();
                    var authentication = new UsernamePasswordAuthenticationToken(
                            claims.getSubject(), null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (RuntimeException e) {
                    // Invalid/expired token: leave the request unauthenticated.
                    log.debug("Rejected JWT: {}", e.getMessage());
                }
            }
        }
        chain.doFilter(request, response);
    }
}
