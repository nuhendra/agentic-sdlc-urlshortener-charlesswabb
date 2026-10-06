package com.schwab.urlshortener.dto;

import java.time.Instant;

/**
 * Standard error response body returned by the global exception handler.
 */
public record ApiError(int status, String error, String message, Instant timestamp) {
    /**
     * Builds an error body, stamping it with the current instant.
     *
     * @param status  the HTTP status code
     * @param error   the HTTP reason phrase
     * @param message the human-readable detail
     * @return a populated {@link ApiError}
     */
    public static ApiError of(int status, String error, String message) {
        return new ApiError(status, error, message, Instant.now());
    }
}
