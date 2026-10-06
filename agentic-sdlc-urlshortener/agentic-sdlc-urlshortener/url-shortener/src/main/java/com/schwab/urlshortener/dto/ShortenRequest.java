package com.schwab.urlshortener.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a short URL.
 *
 * @param url        the long URL to shorten
 * @param ttlSeconds optional time-to-live in seconds; {@code null} means no expiry
 */
public record ShortenRequest(
        @NotBlank(message = "url must not be blank")
        @Size(max = 2048, message = "url must be at most 2048 characters")
        String url,

        @Positive(message = "ttlSeconds must be positive when provided")
        @Max(value = 315_360_000L, message = "ttlSeconds must be at most 10 years")
        Long ttlSeconds
) {
}
