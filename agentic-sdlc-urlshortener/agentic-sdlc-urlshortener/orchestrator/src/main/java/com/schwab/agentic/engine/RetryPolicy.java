package com.schwab.agentic.engine;

/**
 * Bounded retry with exponential backoff. {@code maxAttempts} caps total tries per stage so a
 * failing agent cannot loop forever (a safety control); backoff spaces out transient-failure
 * retries.
 */
public record RetryPolicy(int maxAttempts, long baseBackoffMillis) {

    /** @return the default policy: 3 attempts with a 10 ms base backoff */
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, 10);
    }

    /**
     * Computes the backoff for a given attempt (exponential: 1x, 2x, 4x ...).
     *
     * @param attempt the 1-based attempt number
     * @return the backoff in milliseconds
     */
    public long backoffForAttempt(int attempt) {
        // attempt is 1-based; grow 1x, 2x, 4x ...
        return baseBackoffMillis * (1L << Math.max(0, attempt - 1));
    }

    /**
     * Sleeps for this attempt's backoff, preserving interrupt status if interrupted.
     *
     * @param attempt the 1-based attempt number
     */
    public void sleepBackoff(int attempt) {
        try {
            Thread.sleep(backoffForAttempt(attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
