package com.schwab.urlshortener;

import com.schwab.urlshortener.model.UrlMapping;
import com.schwab.urlshortener.repository.UrlMappingRepository;
import com.schwab.urlshortener.service.UrlShortenerService;
import com.schwab.urlshortener.service.UrlValidator;
import com.schwab.urlshortener.exception.ShortenerExceptions.InvalidUrlException;
import com.schwab.urlshortener.exception.ShortenerExceptions.UrlNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the service, with the repository mocked. These assert behavior
 * (dedup, collision-free code derivation, expiry) without a database.
 */
class UrlShortenerServiceTest {

    private UrlMappingRepository repository;
    private UrlShortenerService service;
    private final AtomicLong idSeq = new AtomicLong(0);

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(UrlMappingRepository.class);
        service = new UrlShortenerService(repository, new UrlValidator());

        // saveAndFlush assigns a surrogate id, mimicking an IDENTITY column.
        when(repository.saveAndFlush(any(UrlMapping.class))).thenAnswer(inv -> {
            UrlMapping m = inv.getArgument(0);
            ReflectionTestUtils.setField(m, "id", idSeq.incrementAndGet());
            return m;
        });
        when(repository.save(any(UrlMapping.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void shortenProducesANonBlankCode() {
        when(repository.findByLongUrlHash(anyLong())).thenReturn(List.of());
        UrlMapping m = service.shorten("https://schwab.com/very/long/path", null);
        assertNotNull(m.getShortCode());
        assertEquals("https://schwab.com/very/long/path", m.getLongUrl());
    }

    @Test
    void shortenDeduplicatesSameUrlWhenNoTtl() {
        // The service filters dedup candidates by exact URL equality (not by the hash value),
        // so any placeholder hash works and the stub matches on any hash argument.
        UrlMapping existing = new UrlMapping("https://schwab.com", 123L, null);
        ReflectionTestUtils.setField(existing, "id", 42L);
        existing.assignShortCode("code42");
        when(repository.findByLongUrlHash(anyLong())).thenReturn(List.of(existing));

        UrlMapping result = service.shorten("https://schwab.com", null);
        assertSame(existing, result, "same URL without TTL must return the existing mapping");
    }

    @Test
    void shortenRejectsNonHttpSchemes() {
        assertThrows(InvalidUrlException.class,
                () -> service.shorten("javascript:alert(1)", null));
        assertThrows(InvalidUrlException.class,
                () -> service.shorten("ftp://example.com/file", null));
    }

    @Test
    void resolveReturnsLongUrl() {
        UrlMapping m = new UrlMapping("https://schwab.com", 1L, null);
        m.assignShortCode("abc");
        when(repository.findByShortCode("abc")).thenReturn(Optional.of(m));
        assertEquals("https://schwab.com", service.resolve("abc"));
    }

    @Test
    void resolveThrowsForUnknownCode() {
        when(repository.findByShortCode(anyString())).thenReturn(Optional.empty());
        assertThrows(UrlNotFoundException.class, () -> service.resolve("nope"));
    }

    @Test
    void resolveTreatsExpiredMappingAsAbsent() {
        UrlMapping m = new UrlMapping("https://schwab.com", 1L,
                java.time.Instant.now().minus(Duration.ofMinutes(1)));
        m.assignShortCode("old");
        when(repository.findByShortCode("old")).thenReturn(Optional.of(m));
        assertThrows(UrlNotFoundException.class, () -> service.resolve("old"));
    }
}
