package com.schwab.urlshortener.service;

import com.schwab.urlshortener.model.UrlMapping;
import com.schwab.urlshortener.util.Base62;
import com.schwab.urlshortener.repository.UrlMappingRepository;
import com.schwab.urlshortener.exception.ShortenerExceptions.UrlNotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Core shortener use cases: create a short code for a URL, and resolve a code back to its URL.
 *
 * <p><b>Collision-free codes.</b> We persist first to obtain a unique identity column, then
 * derive the code as {@code Base62(id + OFFSET)}. Because the id is unique by construction,
 * the code is too — there is no generate-and-check loop and no collision retry.
 *
 * <p><b>Guessability.</b> A large fixed offset keeps codes at a stable length and makes raw
 * sequential enumeration less obvious. This is intentionally <i>not</i> a security control
 * (see ARCHITECTURE.md, "Known limitations"): true unguessability would require encrypting
 * the id, which the orchestrator proposes as a brownfield enhancement.
 */
@Service
public class UrlShortenerService {

    /** Offset so the first codes are not "1", "2", ... and have a consistent length. */
    private static final long CODE_OFFSET = 1_000_000_000L;

    private final UrlMappingRepository repository;
    private final UrlValidator validator;

    /**
     * Constructs the service.
     *
     * @param repository the mapping repository
     * @param validator  the URL normalizer/validator
     */
    public UrlShortenerService(UrlMappingRepository repository, UrlValidator validator) {
        this.repository = repository;
        this.validator = validator;
    }

    /**
     * Shortens a URL, returning an existing mapping when the same URL was already shortened
     * without a TTL (deduplication).
     *
     * <p>Flow: validate/normalize the URL, compute its dedup hash, return any existing match,
     * otherwise persist to obtain an id and derive a collision-free Base62 code from it.
     *
     * @param rawUrl the caller-supplied URL
     * @param ttl    the time-to-live, or {@code null} for a non-expiring mapping (only
     *               non-expiring mappings participate in dedup)
     * @return the created or existing mapping, with its short code assigned
     * @throws com.schwab.urlshortener.exception.ShortenerExceptions.InvalidUrlException
     *         if the URL fails validation
     */
    @Transactional
    public UrlMapping shorten(String rawUrl, Duration ttl) {
        String url = validator.normalize(rawUrl);
        long hash = stableHash(url);

        // Deduplicate: same URL (and no TTL) returns the existing mapping. The hash narrows
        // the lookup; the exact compare guards against hash collisions.
        if (ttl == null) {
            Optional<UrlMapping> existing = repository.findByLongUrlHash(hash).stream()
                    .filter(m -> m.getLongUrl().equals(url) && m.getExpiresAt() == null)
                    .findFirst();
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        Instant expiresAt = ttl == null ? null : Instant.now().plus(ttl);
        UrlMapping mapping = repository.saveAndFlush(new UrlMapping(url, hash, expiresAt));
        mapping.assignShortCode(Base62.encode(mapping.getId() + CODE_OFFSET));
        return repository.save(mapping);
    }

    /**
     * Resolves a short code to its long URL.
     *
     * <p>Cached because mappings are effectively immutable once created, which makes the
     * read-hot redirect path a near-perfect cache candidate.
     *
     * @param code the short code to resolve
     * @return the destination long URL
     * @throws UrlNotFoundException if no mapping exists for the code, or it has expired
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "resolvedUrls", key = "#code")
    public String resolve(String code) {
        UrlMapping mapping = repository.findByShortCode(code)
                .orElseThrow(() -> new UrlNotFoundException(code));
        if (mapping.isExpired(Instant.now())) {
            throw new UrlNotFoundException(code);
        }
        return mapping.getLongUrl();
    }

    /**
     * Fetches the full mapping for a code (used by the stats endpoint).
     *
     * @param code the short code
     * @return the mapping
     * @throws UrlNotFoundException if no mapping exists for the code
     */
    @Transactional(readOnly = true)
    public UrlMapping getMapping(String code) {
        return repository.findByShortCode(code)
                .orElseThrow(() -> new UrlNotFoundException(code));
    }

    /**
     * Increments the stored click counter for a code under optimistic locking. A missing code
     * is a no-op (the redirect has already been served; analytics must never fail the request).
     *
     * @param code the short code whose counter to bump
     */
    @Transactional
    public void incrementClickCount(String code) {
        repository.findByShortCode(code).ifPresent(UrlMapping::incrementClicks);
    }

    /**
     * Returns the mappings sharing a given URL hash (package-visible test/diagnostic seam).
     *
     * @param hash the stable URL hash
     * @return matching mappings (possibly empty)
     */
    List<UrlMapping> findByHash(long hash) {
        return repository.findByLongUrlHash(hash);
    }

    /**
     * Computes a stable 64-bit FNV-1a hash of a URL for dedup indexing.
     *
     * <p>{@link String#hashCode()} is only 32-bit and not guaranteed stable across JVMs; this
     * folds a 64-bit FNV-1a so the stored hash is deterministic and wide.
     *
     * @param s the URL string
     * @return the 64-bit hash
     */
    static long stableHash(String s) {
        final long fnvOffset = 0xcbf29ce484222325L;
        final long fnvPrime = 0x100000001b3L;
        long hash = fnvOffset;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= fnvPrime;
        }
        return hash;
    }
}
