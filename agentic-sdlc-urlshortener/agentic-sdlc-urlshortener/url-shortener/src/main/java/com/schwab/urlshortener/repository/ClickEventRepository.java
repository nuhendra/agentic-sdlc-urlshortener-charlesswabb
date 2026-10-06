package com.schwab.urlshortener.repository;

import com.schwab.urlshortener.model.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link ClickEvent} rows.
 */
public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    /**
     * Counts recorded click events for a short code.
     *
     * @param shortCode the code whose events to count
     * @return the number of click events recorded for {@code shortCode}
     */
    long countByShortCode(String shortCode);
}
