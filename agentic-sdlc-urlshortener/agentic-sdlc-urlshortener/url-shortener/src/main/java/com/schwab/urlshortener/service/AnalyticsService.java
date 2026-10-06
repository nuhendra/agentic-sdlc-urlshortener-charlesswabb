package com.schwab.urlshortener.service;

import com.schwab.urlshortener.model.ClickEvent;
import com.schwab.urlshortener.repository.ClickEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Records click/redirect analytics asynchronously.
 *
 * <p>Recording happens on a separate thread pool ({@code @Async}) so that writing an
 * analytics row never adds latency to, or can fail, a user-facing redirect. A failure to
 * record analytics is logged and swallowed — losing a metric must not break a redirect.
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final ClickEventRepository clickEventRepository;

    /**
     * Constructs the analytics service.
     *
     * @param clickEventRepository the repository that persists click events
     */
    public AnalyticsService(ClickEventRepository clickEventRepository) {
        this.clickEventRepository = clickEventRepository;
    }

    /**
     * Persists a click event on a background thread.
     *
     * <p>Runs on the {@code analyticsExecutor} pool and swallows (logs) any failure, so a
     * problem writing analytics can never propagate to the redirect that triggered it.
     *
     * @param shortCode the code that was resolved
     * @param userAgent the client User-Agent header, or {@code null}
     * @param referrer  the client Referer header, or {@code null}
     */
    @Async("analyticsExecutor")
    public void recordClick(String shortCode, String userAgent, String referrer) {
        try {
            clickEventRepository.save(new ClickEvent(
                    shortCode,
                    Instant.now(),
                    truncate(userAgent, 512),
                    truncate(referrer, 64)));
        } catch (RuntimeException e) {
            log.warn("Failed to record click for code={} (non-fatal): {}", shortCode, e.getMessage());
        }
    }

    /**
     * Truncates a string to a maximum length, tolerating nulls.
     *
     * @param s   the value to truncate, or {@code null}
     * @param max the maximum retained length
     * @return {@code null} if the input was null, otherwise the input capped at {@code max} chars
     */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
