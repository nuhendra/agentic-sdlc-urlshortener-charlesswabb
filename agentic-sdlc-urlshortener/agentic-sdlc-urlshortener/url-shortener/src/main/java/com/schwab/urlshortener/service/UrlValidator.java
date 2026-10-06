package com.schwab.urlshortener.service;

import com.schwab.urlshortener.exception.ShortenerExceptions.InvalidUrlException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * Normalizes and validates submitted URLs before they are stored.
 *
 * <p>Only {@code http} and {@code https} schemes are accepted. This is a security control:
 * it prevents the shortener from being used to cloak {@code javascript:}, {@code data:},
 * or {@code file:} URIs behind a trusted-looking short link.
 */
@Component
public class UrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /**
     * Trims, parses, and validates a raw URL string.
     *
     * @param raw the caller-supplied URL
     * @return the trimmed, validated URL string, safe to persist
     * @throws InvalidUrlException if the value is blank, not a valid URI, has a scheme other
     *                             than http/https, or has no host
     */
    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidUrlException("url must not be blank");
        }
        String trimmed = raw.trim();
        final URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("url is not a valid URI: " + e.getMessage());
        }
        if (uri.getScheme() == null || !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase())) {
            throw new InvalidUrlException("url scheme must be http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidUrlException("url must have a host");
        }
        return trimmed;
    }
}
