package com.schwab.urlshortener.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;

/**
 * Infrastructure beans: the resolve-path cache and the analytics executor.
 */
@Configuration
@EnableCaching
public class AppConfig {

    /**
     * Caffeine-backed cache manager for the {@code resolvedUrls} cache used by the hot
     * redirect path.
     *
     * @return a cache manager bounded to 100k entries with a 24-hour write TTL
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("resolvedUrls");
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(Duration.ofHours(24)));
        return manager;
    }

    /**
     * Dedicated pool for async analytics writes. Virtual threads (Java 21) are a natural fit:
     * the work is a short, blocking JDBC insert, and virtual threads let us run many such
     * blocking tasks cheaply without sizing a platform-thread pool.
     *
     * @return the executor backing {@code @Async("analyticsExecutor")} methods
     */
    @Bean("analyticsExecutor")
    public AsyncTaskExecutor analyticsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(10_000);
        executor.setThreadNamePrefix("analytics-");
        executor.initialize();
        return executor;
    }
}
