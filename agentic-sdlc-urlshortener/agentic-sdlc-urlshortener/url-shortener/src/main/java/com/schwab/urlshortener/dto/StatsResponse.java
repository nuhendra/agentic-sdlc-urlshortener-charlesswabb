package com.schwab.urlshortener.dto;

import com.schwab.urlshortener.model.UrlMapping;

import java.time.Instant;

/**
 * Response body for click/usage statistics of a short code.
 */
public record StatsResponse(
        String shortCode,
        String longUrl,
        long clickCount,
        Instant createdAt,
        Instant expiresAt
) {
    /**
     * Builds a stats response from a persisted mapping.
     *
     * @param m the saved mapping
     * @return a populated {@link StatsResponse}
     */
    public static StatsResponse from(UrlMapping m) {
        return new StatsResponse(
                m.getShortCode(),
                m.getLongUrl(),
                m.getClickCount(),
                m.getCreatedAt(),
                m.getExpiresAt());
    }
}
