package com.schwab.urlshortener.controller;

import com.schwab.urlshortener.service.AnalyticsService;
import com.schwab.urlshortener.model.UrlMapping;
import com.schwab.urlshortener.service.UrlShortenerService;
import com.schwab.urlshortener.dto.ShortenRequest;
import com.schwab.urlshortener.dto.ShortenResponse;
import com.schwab.urlshortener.dto.StatsResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;

/**
 * Public HTTP API for the shortener.
 *
 * <ul>
 *   <li>{@code POST /api/v1/urls} — create a short code; returns 201 Created.</li>
 *   <li>{@code GET /{code}} — redirect; returns 302 Found so clicks keep hitting the
 *       server and analytics stays accurate (301 would be cached by browsers).</li>
 *   <li>{@code GET /api/v1/urls/{code}} — fetch stats for a code.</li>
 * </ul>
 */
@RestController
public class UrlController {

    private final UrlShortenerService service;
    private final AnalyticsService analytics;
    private final String baseUrl;

    /**
     * Constructs the controller.
     *
     * @param service   the shortener service
     * @param analytics the asynchronous analytics recorder
     * @param baseUrl   the public base URL used to build absolute short links
     */
    public UrlController(UrlShortenerService service,
                         AnalyticsService analytics,
                         @Value("${shortener.base-url:http://localhost:8080}") String baseUrl) {
        this.service = service;
        this.analytics = analytics;
        this.baseUrl = baseUrl;
    }

    /**
     * Creates a short URL.
     *
     * @param request the validated request body containing the URL and optional TTL
     * @return 201 Created with the {@link ShortenResponse} body and a {@code Location} header
     */
    @PostMapping("/api/v1/urls")
    public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest request) {
        Duration ttl = request.ttlSeconds() == null ? null : Duration.ofSeconds(request.ttlSeconds());
        UrlMapping mapping = service.shorten(request.url(), ttl);
        return ResponseEntity
                .created(URI.create(baseUrl + "/" + mapping.getShortCode()))
                .body(ShortenResponse.from(mapping, baseUrl));
    }

    /**
     * Resolves a code and redirects to its destination, recording the click off the hot path.
     *
     * @param code    the short code from the path
     * @param request the servlet request, used to capture User-Agent/Referer for analytics
     * @return 302 Found with a {@code Location} header pointing at the long URL
     * @throws com.schwab.urlshortener.exception.ShortenerExceptions.UrlNotFoundException
     *         if the code is unknown or expired (mapped to 404 by the global handler)
     */
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable("code") String code, HttpServletRequest request) {
        String longUrl = service.resolve(code);
        // Fire-and-forget analytics + counter; neither blocks the redirect.
        analytics.recordClick(code, request.getHeader("User-Agent"), request.getHeader("Referer"));
        service.incrementClickCount(code);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(longUrl)).build();
    }

    /**
     * Returns usage statistics for a code.
     *
     * @param code the short code from the path
     * @return 200 OK with the {@link StatsResponse} body
     * @throws com.schwab.urlshortener.exception.ShortenerExceptions.UrlNotFoundException
     *         if the code is unknown (mapped to 404 by the global handler)
     */
    @GetMapping("/api/v1/urls/{code}")
    public ResponseEntity<StatsResponse> stats(@PathVariable("code") String code) {
        return ResponseEntity.ok(StatsResponse.from(service.getMapping(code)));
    }
}
