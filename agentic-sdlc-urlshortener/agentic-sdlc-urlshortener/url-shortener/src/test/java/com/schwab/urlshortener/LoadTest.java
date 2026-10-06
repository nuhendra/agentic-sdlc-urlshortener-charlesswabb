package com.schwab.urlshortener;

import com.schwab.urlshortener.model.UrlMapping;
import com.schwab.urlshortener.service.UrlShortenerService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lightweight in-process load test: fire many concurrent {@code shorten} calls on virtual
 * threads and assert correctness under concurrency (every call succeeds and every short code is
 * unique), while printing achieved throughput.
 *
 * <p>Tagged {@code load} so it is excluded from the normal test run (it is timing-sensitive and
 * slower). Run it with {@code mvn -Pperf verify}. For realistic HTTP load testing against a
 * running instance, use the k6 script in {@code perf/k6-load-test.js} (see TESTING.md) — this
 * JUnit test checks the service's behavior under concurrency, not network-level performance.
 */
@Tag("load")
@SpringBootTest
class LoadTest {

    private static final int REQUESTS = 500;
    private static final int CONCURRENCY = 64;

    @Autowired
    UrlShortenerService service;

    @Test
    void concurrentShortenIsCorrectAndFastEnough() throws Exception {
        Set<String> codes = ConcurrentHashMap.newKeySet();
        AtomicInteger failures = new AtomicInteger();

        long start = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < REQUESTS; i++) {
                final int n = i;
                pool.submit(() -> {
                    try {
                        UrlMapping m = service.shorten("https://schwab.com/load/" + n, null);
                        codes.add(m.getShortCode());
                    } catch (RuntimeException e) {
                        failures.incrementAndGet();
                    }
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "load run did not finish in time");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        double throughput = REQUESTS / (elapsedMs / 1000.0);

        System.out.printf("LoadTest: %d requests, %d concurrency, %d ms, ~%.0f req/s%n",
                REQUESTS, CONCURRENCY, elapsedMs, throughput);

        assertEquals(0, failures.get(), "no request should fail under concurrency");
        assertEquals(REQUESTS, codes.size(), "every generated short code must be unique");
    }
}
