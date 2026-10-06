package com.schwab.urlshortener.dto;

import com.schwab.urlshortener.model.UrlMapping;

import java.time.Instant;

/**
 * Response body returned when a short URL is created.
 */
public record ShortenResponse(
        String shortCode,
        String shortUrl,
        String longUrl,
        Instant createdAt,
        Instant expiresAt
) {
    /**
     * Builds a response from a persisted mapping.
     *
     * @param m       the saved mapping (short code already assigned)
     * @param baseUrl the public base URL used to form the absolute short link
     * @return a populated {@link ShortenResponse}
     */
    public static ShortenResponse from(UrlMapping m, String baseUrl) {
        return new ShortenResponse(
                m.getShortCode(),
                baseUrl + "/" + m.getShortCode(),
                m.getLongUrl(),
                m.getCreatedAt(),
                m.getExpiresAt());
    }
}
