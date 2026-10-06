package com.schwab.urlshortener.exception;

/**
 * Domain exceptions for the shortener, grouped in one file for readability.
 */
public final class ShortenerExceptions {

    /** Non-instantiable container for the exception types. */
    private ShortenerExceptions() {
    }

    /** Requested short code does not exist (or has expired). Maps to HTTP 404. */
    public static class UrlNotFoundException extends RuntimeException {
        /**
         * @param code the short code that could not be resolved
         */
        public UrlNotFoundException(String code) {
            super("No active mapping for code: " + code);
        }
    }

    /** Submitted URL failed validation/normalization. Maps to HTTP 400. */
    public static class InvalidUrlException extends RuntimeException {
        /**
         * @param message the specific validation failure
         */
        public InvalidUrlException(String message) {
            super(message);
        }
    }

    /** Caller exceeded the rate limit. Maps to HTTP 429. */
    public static class RateLimitExceededException extends RuntimeException {
        /**
         * @param message the limit that was exceeded
         */
        public RateLimitExceededException(String message) {
            super(message);
        }
    }
}
