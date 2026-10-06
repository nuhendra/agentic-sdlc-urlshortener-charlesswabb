package com.schwab.urlshortener.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A single resolve/redirect event, written asynchronously off the hot path so that
 * analytics never adds latency to a redirect.
 */
@Entity
@Table(name = "click_event", indexes = @Index(name = "idx_click_code", columnList = "shortCode"))
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String shortCode;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(length = 512)
    private String userAgent;

    @Column(length = 64)
    private String referrer;

    /** No-args constructor required by JPA; not for application use. */
    protected ClickEvent() {
    }

    /**
     * Creates a click event record.
     *
     * @param shortCode  the code that was resolved
     * @param occurredAt the instant the click occurred
     * @param userAgent  the requesting client's User-Agent header, or {@code null}
     * @param referrer   the requesting client's Referer header, or {@code null}
     */
    public ClickEvent(String shortCode, Instant occurredAt, String userAgent, String referrer) {
        this.shortCode = shortCode;
        this.occurredAt = occurredAt;
        this.userAgent = userAgent;
        this.referrer = referrer;
    }

    /** Returns the surrogate primary key, or {@code null} before the row is persisted. */
    public Long getId() {
        return id;
    }

    /** Returns the short code that was resolved. */
    public String getShortCode() {
        return shortCode;
    }

    /** Returns the instant the click occurred. */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /** Returns the captured User-Agent header, or {@code null} if absent. */
    public String getUserAgent() {
        return userAgent;
    }

    /** Returns the captured Referer header, or {@code null} if absent. */
    public String getReferrer() {
        return referrer;
    }
}
