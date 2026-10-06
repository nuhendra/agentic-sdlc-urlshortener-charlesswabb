package com.schwab.urlshortener.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simple fixed-window per-client rate limiter for write (shorten) requests.
 *
 * <p>This is a reliability control: it protects the id sequence and database from abusive
 * bursts. The window counters live in an expiring Caffeine cache so memory is bounded. In a
 * multi-instance deployment this would move to a shared store (e.g. Redis) — called out as a
 * known limitation in ARCHITECTURE.md.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private final int maxRequestsPerWindow;
    private final Cache<String, AtomicInteger> windows;

    /**
     * Constructs the filter with window parameters.
     *
     * @param maxRequestsPerWindow the maximum allowed write requests per client per window
     * @param windowSeconds        the fixed-window length, in seconds
     */
    public RateLimitFilter(
            @Value("${shortener.ratelimit.max-per-window:100}") int maxRequestsPerWindow,
            @Value("${shortener.ratelimit.window-seconds:60}") long windowSeconds) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.windows = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(windowSeconds))
                .maximumSize(100_000)
                .build();
    }

    /**
     * Limits the filter to write requests only; reads and redirects are cheap and cacheable.
     *
     * @param request the current request
     * @return {@code true} to skip rate limiting (i.e. not a POST to {@code /api/v1/urls})
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().startsWith("/api/v1/urls"));
    }

    /**
     * Counts the request against the client's current window and rejects with 429 once the
     * limit is exceeded; otherwise passes the request down the chain.
     *
     * @param request  the current request
     * @param response the response to write a 429 to when the limit is hit
     * @param chain    the remaining filter chain
     * @throws ServletException if the downstream chain fails
     * @throws IOException      if writing the response fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String clientKey = clientKey(request);
        AtomicInteger counter = windows.get(clientKey, k -> new AtomicInteger(0));
        if (counter.incrementAndGet() > maxRequestsPerWindow) {
            // Servlet's HttpServletResponse has no SC_ constant for 429 (post-dates the spec),
            // so use Spring's HttpStatus.
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\","
                            + "\"message\":\"rate limit exceeded; retry later\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Derives the client key for a request, preferring the first {@code X-Forwarded-For} hop
     * (so the limiter works behind a proxy/load balancer) and falling back to the remote address.
     *
     * @param request the current request
     * @return the key used to bucket this client's requests
     */
    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
    }
}
