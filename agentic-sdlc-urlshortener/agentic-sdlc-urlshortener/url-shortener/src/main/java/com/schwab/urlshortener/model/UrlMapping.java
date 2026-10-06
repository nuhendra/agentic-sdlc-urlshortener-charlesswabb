package com.schwab.urlshortener.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Persistent mapping from a generated short code to a long URL.
 *
 * <p>We index {@code longUrlHash} rather than the full (up to 2048 char) URL, because
 * indexing a large varchar is expensive; the hash narrows the dedup lookup and an exact
 * string compare confirms it (guarding against hash collisions).
 */
@Entity
@Table(
        name = "url_mapping",
        indexes = {
                @Index(name = "idx_short_code", columnList = "shortCode", unique = true),
                @Index(name = "idx_long_url_hash", columnList = "longUrlHash")
        }
)
public class UrlMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The generated short code. Nullable at the column level only to permit the two-phase
     * create (insert to obtain the identity, derive {@code Base62(id)}, then update) within a
     * single transaction; it is always non-null by commit. An alternative that keeps a NOT NULL
     * column is to pre-allocate the id from a sequence and insert once — see ARCHITECTURE.md.
     */
    @Column(unique = true, length = 16)
    private String shortCode;

    @Column(nullable = false, length = 2048)
    private String longUrl;

    @Column(nullable = false)
    private long longUrlHash;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant expiresAt;

    @Column(nullable = false)
    private long clickCount = 0L;

    /** Optimistic lock: protects the click-count read-modify-write from lost updates. */
    @Version
    private long version;

    /** No-args constructor required by JPA; not for application use. */
    protected UrlMapping() {
        // for JPA
    }

    /**
     * Creates a new mapping for a validated long URL.
     *
     * <p>The short code is <i>not</i> set here: it is derived from the surrogate id after the
     * first persist (see {@code UrlShortenerService}), which is what makes codes collision-free.
     *
     * @param longUrl     the validated destination URL; must not be null
     * @param longUrlHash a stable 64-bit hash of {@code longUrl} used for dedup indexing
     * @param expiresAt   the instant the mapping expires, or {@code null} for no expiry
     */
    public UrlMapping(String longUrl, long longUrlHash, Instant expiresAt) {
        this.longUrl = longUrl;
        this.longUrlHash = longUrlHash;
        this.expiresAt = expiresAt;
    }

    /**
     * Assigns the generated short code once the surrogate id is known.
     *
     * @param shortCode the Base62 code derived from this row's id
     */
    public void assignShortCode(String shortCode) {
        this.shortCode = shortCode;
    }

    /** Increments the stored click counter by one. */
    public void incrementClicks() {
        this.clickCount++;
    }

    /**
     * Reports whether this mapping has expired relative to the supplied instant.
     *
     * @param now the reference instant to compare against (typically {@code Instant.now()})
     * @return {@code true} if an expiry is set and {@code now} is strictly after it
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    /** Returns the surrogate primary key, or {@code null} before the row is persisted. */
    public Long getId() {
        return id;
    }

    /** Returns the generated short code, or {@code null} before it is assigned. */
    public String getShortCode() {
        return shortCode;
    }

    /** Returns the destination (long) URL. */
    public String getLongUrl() {
        return longUrl;
    }

    /** Returns the stable 64-bit hash of the long URL used for dedup lookups. */
    public long getLongUrlHash() {
        return longUrlHash;
    }

    /** Returns the instant this mapping was created. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Returns the expiry instant, or {@code null} if the mapping never expires. */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** Returns the total number of resolved clicks recorded for this mapping. */
    public long getClickCount() {
        return clickCount;
    }

    /** Returns the optimistic-locking version counter. */
    public long getVersion() {
        return version;
    }
}
