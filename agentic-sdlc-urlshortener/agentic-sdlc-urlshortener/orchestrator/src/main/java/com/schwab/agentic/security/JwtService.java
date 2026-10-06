package com.schwab.agentic.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * Issues and verifies self-signed HS256 JSON Web Tokens.
 *
 * <p>Tokens carry the username as the subject and the granted authorities (each prefixed
 * {@code ROLE_}) in a {@code roles} claim. Verification uses the same shared HMAC secret.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationSeconds;

    /**
     * @param props security configuration supplying the HMAC secret and token lifetime
     */
    public JwtService(SecurityProperties props) {
        this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = props.jwt().expirationSeconds();
    }

    /**
     * Issues a signed token for a principal.
     *
     * @param username the subject
     * @param roles    the granted authorities (e.g. {@code ROLE_AGENT})
     * @return a compact, signed JWT string
     */
    public String generateToken(String username, Collection<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("roles", List.copyOf(roles))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * Verifies a token's signature and expiry and returns its claims.
     *
     * @param token the compact JWT string (without the {@code Bearer } prefix)
     * @return the verified claims
     * @throws io.jsonwebtoken.JwtException if the token is invalid, tampered, or expired
     */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /** @return the configured token lifetime in seconds */
    public long expirationSeconds() {
        return expirationSeconds;
    }
}
