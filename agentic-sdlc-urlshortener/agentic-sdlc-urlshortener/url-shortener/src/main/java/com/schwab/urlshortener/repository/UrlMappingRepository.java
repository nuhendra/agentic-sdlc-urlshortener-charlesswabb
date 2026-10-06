package com.schwab.urlshortener.repository;

import com.schwab.urlshortener.model.UrlMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link UrlMapping} rows.
 */
public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    /**
     * Finds the mapping for a short code.
     *
     * @param shortCode the generated short code
     * @return the mapping if present, otherwise {@link Optional#empty()}
     */
    Optional<UrlMapping> findByShortCode(String shortCode);

    /**
     * Returns dedup candidates for a URL by its hash. The service confirms a true match with an
     * exact URL string compare, since distinct URLs may share a hash.
     *
     * @param longUrlHash the stable 64-bit hash of the long URL
     * @return all mappings whose stored hash equals {@code longUrlHash} (possibly empty)
     */
    List<UrlMapping> findByLongUrlHash(long longUrlHash);

    /**
     * Reports whether a short code is already taken.
     *
     * @param shortCode the code to check
     * @return {@code true} if a mapping with that code exists
     */
    boolean existsByShortCode(String shortCode);
}
